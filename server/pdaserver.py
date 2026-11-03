#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
S.T.A.L.K.E.R. PDA - сервер глобальної мережі.

Запуск:   python3 pdaserver.py          - текстове меню
          python3 pdaserver.py --run    - просто запустити сервер (для служби/автозапуску)
Потрібно: Python 3.8+ і пакет cryptography (pip install cryptography).

Безпека: TLS 1.2+, застосунок і сервери перевіряють сертифікат за відбитком (SHA-256, "pinning"),
користувачі входять за токеном (на сервері лишається тільки його хеш), сервери довіряють один
одному за секретом із запрошення (HMAC-перевірка, секрет по мережі не передається).
"""
import asyncio
import base64
import collections
import datetime
import hashlib
import hmac
import json
import os
import re
import secrets
import ssl
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.environ.get("PDA_DATA", os.path.join(HERE, "data"))
MAX_LINE = 128 * 1024          # максимальний розмір одного повідомлення
ROUTE_TTL = 8                  # скільки "стрибків" між серверами
SPOOL_DAYS = 7
SPOOL_MAX = 500
USER_RATE = 300                # повідомлень за секунду на користувача, більше - відключаємо
MAX_CONN_PER_IP = 30
ID_RE = re.compile(r"^[A-Za-z0-9_-]{6,32}$")


def now():
    return time.time()


def log(msg):
    print(time.strftime("%Y-%m-%d %H:%M:%S"), msg, flush=True)


def b64e(obj):
    raw = json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")


def b64d(text):
    text = text.strip()
    pad = "=" * (-len(text) % 4)
    return json.loads(base64.urlsafe_b64decode(text + pad).decode("utf-8"))


def sha256(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def proof_of(secret, nonce, sid):
    return hmac.new(secret.encode("utf-8"), (nonce + sid).encode("utf-8"), hashlib.sha256).hexdigest()


# ───────────────────────── сховище ─────────────────────────

class Store:
    def __init__(self, data_dir):
        self.dir = data_dir
        self.spool = os.path.join(data_dir, "spool")
        os.makedirs(self.spool, exist_ok=True)
        self.cfg_path = os.path.join(data_dir, "config.json")
        self.users_path = os.path.join(data_dir, "users.json")
        self.cert_path = os.path.join(data_dir, "cert.pem")
        self.key_path = os.path.join(data_dir, "key.pem")
        self.status_path = os.path.join(data_dir, "status.json")
        self.names_path = os.path.join(data_dir, "names.json")

    @staticmethod
    def _load(path, default):
        try:
            with open(path, "r", encoding="utf-8") as f:
                return json.load(f)
        except (OSError, ValueError):
            return default

    @staticmethod
    def _save(path, obj):
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(obj, f, ensure_ascii=False, indent=1)
        os.replace(tmp, path)

    def load_cfg(self):
        cfg = self._load(self.cfg_path, None)
        if cfg is None:
            cfg = {"server_id": secrets.token_hex(4), "name": "pda-server", "host": "", "port": 8443,
                   "peers": [], "invites": []}
            self._save(self.cfg_path, cfg)
        for k, v in (("peers", []), ("invites", []), ("host", ""), ("port", 8443), ("name", "pda-server")):
            cfg.setdefault(k, v)
        return cfg

    def save_cfg(self, cfg):
        self._save(self.cfg_path, cfg)

    def load_users(self):
        return self._load(self.users_path, {})

    def save_users(self, users):
        self._save(self.users_path, users)

    def load_names(self):
        return self._load(self.names_path, {})

    def save_names(self, names):
        self._save(self.names_path, names)

    def mtime(self, path):
        try:
            return os.path.getmtime(path)
        except OSError:
            return 0.0

    # сертифікат
    def ensure_cert(self):
        if os.path.exists(self.cert_path) and os.path.exists(self.key_path):
            return
        from cryptography import x509
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import ec
        from cryptography.x509.oid import NameOID
        cfg = self.load_cfg()
        key = ec.generate_private_key(ec.SECP256R1())
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "pda-" + cfg["server_id"])])
        t0 = datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(days=1)
        cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name)
                .public_key(key.public_key()).serial_number(x509.random_serial_number())
                .not_valid_before(t0).not_valid_after(t0 + datetime.timedelta(days=3650))
                .sign(key, hashes.SHA256()))
        with open(self.key_path, "wb") as f:
            f.write(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                      serialization.NoEncryption()))
        try:
            os.chmod(self.key_path, 0o600)
        except OSError:
            pass
        with open(self.cert_path, "wb") as f:
            f.write(cert.public_bytes(serialization.Encoding.PEM))

    def fingerprint(self):
        with open(self.cert_path, "rb") as f:
            pem = f.read().decode()
        der = ssl.PEM_cert_to_DER_cert(pem)
        return hashlib.sha256(der).hexdigest()


# ───────────────────────── з'єднання ─────────────────────────

class Conn:
    """Вихідна черга + писар: повільний клієнт не гальмує інших."""

    def __init__(self, reader, writer, kind, ident, ip):
        self.reader, self.writer, self.kind, self.ident, self.ip = reader, writer, kind, ident, ip
        self.q = asyncio.Queue(maxsize=3000)
        self.closed = False
        self.task = asyncio.ensure_future(self._pump())

    def send(self, obj):
        if self.closed:
            return
        line = json.dumps(obj, ensure_ascii=False, separators=(",", ":")) + "\n"
        try:
            self.q.put_nowait(line)
        except asyncio.QueueFull:
            self.close()

    async def _pump(self):
        try:
            while True:
                line = await self.q.get()
                if line is None:
                    break
                self.writer.write(line.encode("utf-8"))
                await asyncio.wait_for(self.writer.drain(), 20)
        except Exception:
            pass
        finally:
            self.close()

    def close(self):
        if self.closed:
            return
        self.closed = True
        try:
            self.q.put_nowait(None)
        except asyncio.QueueFull:
            pass
        try:
            self.writer.close()
        except Exception:
            pass


# ───────────────────────── сервер ─────────────────────────

class Server:
    def __init__(self, store):
        self.store = store
        self.cfg = store.load_cfg()
        self.users = store.load_users()
        self.names = store.load_names()       # унікальні позивні на всю мережу серверів
        self.fp = store.fingerprint()
        self.online = {}                      # uid -> Conn
        self.links = {}                       # server_id -> [Conn]
        self.dialers = {}                     # server_id -> task
        self.seen = collections.OrderedDict()
        self.fails = {}                       # ip -> [count, first_ts]
        self.per_ip = collections.Counter()
        self.mt = {}

    # ----- службове -----
    def reload(self):
        for path, attr, loader in ((self.store.cfg_path, "cfg", self.store.load_cfg),
                                   (self.store.users_path, "users", self.store.load_users),
                                   (self.store.names_path, "names", self.store.load_names)):
            m = self.store.mtime(path)
            if m != self.mt.get(path):
                self.mt[path] = m
                setattr(self, attr, loader())

    def banned(self, ip):
        rec = self.fails.get(ip)
        if not rec:
            return False
        if now() - rec[1] > 300:
            del self.fails[ip]
            return False
        return rec[0] >= 5

    def note_fail(self, ip):
        rec = self.fails.get(ip)
        if not rec or now() - rec[1] > 300:
            self.fails[ip] = [1, now()]
        else:
            rec[0] += 1

    # ----- спул для офлайн-користувачів -----
    def spool_file(self, uid):
        return os.path.join(self.store.spool, re.sub(r"[^A-Za-z0-9_-]", "_", uid) + ".jsonl")

    def spool_add(self, uid, pkt):
        path = self.spool_file(uid)
        with open(path, "a", encoding="utf-8") as f:
            f.write(json.dumps({"t": now(), "pkt": pkt}, ensure_ascii=False) + "\n")
        try:
            if os.path.getsize(path) > 3 * 1024 * 1024:
                with open(path, "r", encoding="utf-8") as f:
                    lines = f.readlines()
                with open(path, "w", encoding="utf-8") as f:
                    f.writelines(lines[-SPOOL_MAX:])
        except OSError:
            pass

    def spool_deliver(self, uid, conn):
        path = self.spool_file(uid)
        if not os.path.exists(path):
            return
        count = 0
        try:
            with open(path, "r", encoding="utf-8") as f:
                for line in f:
                    try:
                        rec = json.loads(line)
                    except ValueError:
                        continue
                    if now() - rec.get("t", 0) < SPOOL_DAYS * 86400:
                        conn.send({"op": "pkt", "pkt": rec["pkt"]})
                        count += 1
            os.remove(path)
        except OSError:
            pass
        if count:
            log("спул: доставлено %d повідомлень користувачу %s" % (count, uid))

    # ----- унікальні нікнейми -----
    def _save_names(self):
        self.store.save_names(self.names)
        self.mt[self.store.names_path] = self.store.mtime(self.store.names_path)

    def flood_names(self, lst, drop, origin):
        if not lst and not drop:
            return
        msg = {"op": "names", "list": lst, "drop": drop}
        for sid, conns in list(self.links.items()):
            if sid == origin:
                continue
            alive = [c for c in conns if not c.closed]
            if alive:
                alive[0].send(msg)

    def claim_name(self, uid, name):
        """Позивний належить тому, хто зайняв його першим; другий отримує відмову і обирає інший."""
        name = name.strip()
        if not (2 <= len(name) <= 24) or any(ord(ch) < 32 for ch in name):
            return False
        cf = name.casefold()
        cur = self.names.get(cf)
        if cur and cur["uid"] != uid:
            return False
        dropped = []
        for k, v in list(self.names.items()):
            if v["uid"] == uid and k != cf:
                dropped.append({"cf": k, "uid": uid})
                del self.names[k]
        entry = []
        if not cur:
            self.names[cf] = {"cf": cf, "name": name, "uid": uid, "ts": now()}
            entry = [self.names[cf]]
        elif cur["name"] != name:
            cur["name"] = name
            entry = [cur]
        if entry or dropped:
            self._save_names()
            self.flood_names(entry, dropped, None)
        return True

    def merge_names(self, lst, drop, origin):
        """Злиття з іншим сервером: раніша заявка виграє (при рівності - менший ID)."""
        if not isinstance(lst, list) or not isinstance(drop, list):
            return
        out_list, out_drop = [], []
        for d in drop[:500]:
            if not isinstance(d, dict):
                continue
            cur = self.names.get(str(d.get("cf")))
            if cur and cur["uid"] == d.get("uid"):
                del self.names[cur["cf"]]
                out_drop.append(d)
        for e in lst[:2000]:
            if not isinstance(e, dict):
                continue
            name, uid, cf = e.get("name"), e.get("uid"), e.get("cf")
            try:
                ts = float(e.get("ts"))
            except (TypeError, ValueError):
                continue
            if not (isinstance(name, str) and isinstance(uid, str) and isinstance(cf, str)):
                continue
            if not (2 <= len(name) <= 24) or cf != name.casefold() or not ID_RE.match(uid):
                continue
            cur = self.names.get(cf)
            if cur is None or (ts, uid) < (cur["ts"], cur["uid"]):
                if cur is not None and cur["uid"] != uid:
                    c = self.online.get(cur["uid"])
                    if c:
                        c.send({"op": "name_lost", "name": cur["name"]})
                self.names[cf] = {"cf": cf, "name": name, "uid": uid, "ts": ts}
                out_list.append(self.names[cf])
        if out_list or out_drop:
            self._save_names()
            self.flood_names(out_list, out_drop, origin)

    # ----- маршрутизація -----
    def route(self, sender, to, mid, pkt, spool, ttl, origin):
        key = sender + ":" + mid
        if key in self.seen:
            return
        self.seen[key] = now()
        while len(self.seen) > 30000:
            self.seen.popitem(last=False)
        pkt = dict(pkt)
        pkt["id"] = sender                    # відправника ставить сервер, підробити не можна
        local = False
        if to is None:
            for uid, c in list(self.online.items()):
                if uid != sender:
                    c.send({"op": "pkt", "pkt": pkt})
        elif to in self.users:
            local = True
            c = self.online.get(to)
            if c:
                c.send({"op": "pkt", "pkt": pkt})
            elif spool:
                self.spool_add(to, pkt)
        if ttl > 0 and not local:
            msg = {"op": "route", "mid": mid, "ttl": ttl - 1, "from": sender, "to": to,
                   "spool": spool, "pkt": pkt}
            for sid, conns in list(self.links.items()):
                if sid == origin:
                    continue
                alive = [c for c in conns if not c.closed]
                if alive:
                    alive[0].send(msg)

    # ----- вхідні з'єднання -----
    async def handle(self, reader, writer):
        peer = writer.get_extra_info("peername") or ("?", 0)
        ip = str(peer[0])
        if self.banned(ip) or self.per_ip[ip] >= MAX_CONN_PER_IP:
            writer.close()
            return
        self.per_ip[ip] += 1
        try:
            nonce = secrets.token_hex(16)
            writer.write((json.dumps({"op": "hi", "server": self.cfg["name"], "sid": self.cfg["server_id"],
                                      "nonce": nonce}) + "\n").encode())
            await writer.drain()
            line = await asyncio.wait_for(reader.readline(), 10)
            if not line:
                return
            msg = json.loads(line)
            op = msg.get("op")
            if op == "auth":
                await self.user_session(reader, writer, ip, msg)
            elif op == "s2s":
                await self.server_session(reader, writer, ip, msg, nonce)
            else:
                self.note_fail(ip)
        except Exception:
            pass
        finally:
            self.per_ip[ip] -= 1
            try:
                writer.close()
            except Exception:
                pass

    async def user_session(self, reader, writer, ip, msg):
        uid = str(msg.get("uid", ""))
        token = str(msg.get("token", ""))
        rec = self.users.get(uid)
        if not rec or not hmac.compare_digest(rec["h"], sha256(token)):
            self.note_fail(ip)
            writer.write(b'{"op":"err","why":"auth"}\n')
            await writer.drain()
            log("відмова у вході: %s (%s)" % (uid, ip))
            return
        conn = Conn(reader, writer, "user", uid, ip)
        old = self.online.get(uid)
        if old:
            old.close()
        self.online[uid] = conn
        conn.send({"op": "ok", "server": self.cfg["name"]})
        self.spool_deliver(uid, conn)
        log("користувач увійшов: %s (%s)" % (uid, ip))
        window, count = int(now()), 0
        try:
            while True:
                line = await asyncio.wait_for(reader.readline(), 100)
                if not line:
                    break
                sec = int(now())
                if sec != window:
                    window, count = sec, 0
                count += 1
                if count > USER_RATE:
                    log("перевищено ліміт, відключено: %s" % uid)
                    break
                try:
                    m = json.loads(line)
                except ValueError:
                    continue
                op = m.get("op")
                if op == "ping":
                    conn.send({"op": "pong"})
                elif op == "claim":
                    nm = str(m.get("name", ""))[:24]
                    conn.send({"op": "claim", "ok": self.claim_name(uid, nm), "name": nm})
                elif op in ("send", "bcast"):
                    pkt = m.get("pkt")
                    if not isinstance(pkt, dict) or not isinstance(pkt.get("t"), str):
                        continue
                    to = m.get("to") if op == "send" else None
                    if op == "send" and not isinstance(to, str):
                        continue
                    mid = str(m.get("mid") or secrets.token_hex(8))[:64]
                    self.route(uid, to, mid, pkt, bool(m.get("spool")), ROUTE_TTL, None)
        except Exception:
            pass
        finally:
            if self.online.get(uid) is conn:
                del self.online[uid]
            conn.close()
            log("користувач вийшов: %s" % uid)

    async def server_session(self, reader, writer, ip, msg, nonce):
        sid = str(msg.get("sid", ""))
        proof = str(msg.get("proof", ""))
        name = str(msg.get("name", ""))[:40]
        addr = msg.get("addr") if isinstance(msg.get("addr"), dict) else {}
        self.cfg = self.store.load_cfg()
        peer = None
        for p in self.cfg["peers"]:
            if p.get("server_id") == sid and hmac.compare_digest(proof_of(p["secret"], nonce, sid), proof):
                peer = p
        if peer is None:
            for inv in list(self.cfg["invites"]):
                if hmac.compare_digest(proof_of(inv["secret"], nonce, sid), proof):
                    peer = {"server_id": sid, "name": name, "host": str(addr.get("host", "")),
                            "port": int(addr.get("port", 0) or 0), "fp": str(addr.get("fp", "")),
                            "secret": inv["secret"]}
                    self.cfg["invites"] = [i for i in self.cfg["invites"] if i["id"] != inv["id"]]
                    self.cfg["peers"] = [p for p in self.cfg["peers"] if p.get("server_id") != sid] + [peer]
                    self.store.save_cfg(self.cfg)
                    self.mt[self.store.cfg_path] = self.store.mtime(self.store.cfg_path)
                    log("новий сервер підключено за запрошенням: %s (%s)" % (name, sid))
                    break
        if peer is None:
            self.note_fail(ip)
            writer.write(b'{"op":"err","why":"auth"}\n')
            await writer.drain()
            log("відмова серверу: %s (%s)" % (sid, ip))
            return
        conn = Conn(reader, writer, "server", sid, ip)
        conn.send({"op": "ok", "sid": self.cfg["server_id"], "name": self.cfg["name"]})
        await self.link_loop(conn, sid)

    async def link_loop(self, conn, sid):
        self.links.setdefault(sid, []).append(conn)
        log("зв'язок із сервером %s активний" % sid)
        conn.send({"op": "names", "list": list(self.names.values()), "drop": []})
        try:
            while True:
                line = await asyncio.wait_for(conn.reader.readline(), 100)
                if not line:
                    break
                try:
                    m = json.loads(line)
                except ValueError:
                    continue
                op = m.get("op")
                if op == "ping":
                    conn.send({"op": "pong"})
                elif op == "names":
                    self.merge_names(m.get("list"), m.get("drop"), sid)
                elif op == "route":
                    pkt, sender = m.get("pkt"), m.get("from")
                    if not isinstance(pkt, dict) or not isinstance(sender, str) or not isinstance(pkt.get("t"), str):
                        continue
                    to = m.get("to")
                    if to is not None and not isinstance(to, str):
                        continue
                    ttl = min(int(m.get("ttl", 0)), ROUTE_TTL)
                    self.route(sender, to, str(m.get("mid"))[:64], pkt, bool(m.get("spool")), ttl, sid)
        except Exception:
            pass
        finally:
            lst = self.links.get(sid, [])
            if conn in lst:
                lst.remove(conn)
            if not lst:
                self.links.pop(sid, None)
            conn.close()
            log("зв'язок із сервером %s розірвано" % sid)

    # ----- вихідні з'єднання до інших серверів -----
    async def dial(self, sid):
        backoff = 2
        while True:
            peer = next((p for p in self.cfg["peers"] if p.get("server_id") == sid), None)
            if not peer or not peer.get("host"):
                return
            writer = None
            try:
                ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
                ctx.check_hostname = False
                ctx.verify_mode = ssl.CERT_NONE
                ctx.minimum_version = ssl.TLSVersion.TLSv1_2
                reader, writer = await asyncio.wait_for(
                    asyncio.open_connection(peer["host"], int(peer["port"]), ssl=ctx, limit=MAX_LINE), 15)
                der = writer.get_extra_info("ssl_object").getpeercert(binary_form=True)
                if hashlib.sha256(der).hexdigest() != peer["fp"].lower().replace(":", ""):
                    raise RuntimeError("відбиток сертифіката не збігається (можлива підміна)")
                hi = json.loads(await asyncio.wait_for(reader.readline(), 10))
                hello = {"op": "s2s", "sid": self.cfg["server_id"], "name": self.cfg["name"],
                         "proof": proof_of(peer["secret"], hi["nonce"], self.cfg["server_id"])}
                if self.cfg.get("host"):
                    hello["addr"] = {"host": self.cfg["host"], "port": self.cfg["port"], "fp": self.fp}
                writer.write((json.dumps(hello) + "\n").encode())
                await writer.drain()
                ok = json.loads(await asyncio.wait_for(reader.readline(), 10))
                if ok.get("op") != "ok":
                    raise RuntimeError("сервер відхилив зв'язок")
                backoff = 2
                conn = Conn(reader, writer, "server", sid, peer["host"])
                await self.link_loop(conn, sid)
            except Exception as e:
                if backoff == 2:
                    log("зв'язок із %s: %s" % (sid, e))
            finally:
                if writer is not None:
                    try:
                        writer.close()
                    except Exception:
                        pass
            await asyncio.sleep(backoff)
            backoff = min(backoff * 2, 60)

    async def maintenance(self):
        while True:
            self.reload()
            wanted = {p["server_id"] for p in self.cfg["peers"] if p.get("host")}
            for sid in wanted:
                t = self.dialers.get(sid)
                if t is None or t.done():
                    self.dialers[sid] = asyncio.ensure_future(self.dial(sid))
            for sid in list(self.dialers):
                if sid not in wanted:
                    self.dialers.pop(sid).cancel()
            try:
                self.store._save(self.store.status_path, {
                    "time": now(), "name": self.cfg["name"],
                    "online": sorted(self.online),
                    "links": sorted(sid for sid, l in self.links.items() if any(not c.closed for c in l))})
            except OSError:
                pass
            for k in [k for k, v in self.seen.items() if now() - v > 3600]:
                self.seen.pop(k, None)
            await asyncio.sleep(5)

    async def serve(self):
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.minimum_version = ssl.TLSVersion.TLSv1_2
        ctx.load_cert_chain(self.store.cert_path, self.store.key_path)
        port = int(self.cfg["port"])
        srv = await asyncio.start_server(self.handle, host=None, port=port, ssl=ctx, limit=MAX_LINE)
        log("сервер «%s» (%s) слухає порт %d" % (self.cfg["name"], self.cfg["server_id"], port))
        log("відбиток сертифіката: %s" % self.fp)
        asyncio.ensure_future(self.maintenance())
        async with srv:
            await srv.serve_forever()


# ───────────────────────── текстове меню ─────────────────────────

def ask(prompt, default=""):
    try:
        v = input(prompt).strip()
    except EOFError:
        return default
    return v or default


def need_host(store, cfg):
    if not cfg.get("host"):
        print("\nСпочатку вкажи адресу сервера (пункт 8): IP або доменне ім'я, яке бачать інші.")
        return False
    return True


def menu(store):
    store.ensure_cert()
    while True:
        cfg = store.load_cfg()
        users = store.load_users()
        print("\n==== S.T.A.L.K.E.R. PDA: сервер «%s» (%s) ====" % (cfg["name"], cfg["server_id"]))
        print("Адреса: %s:%s   Користувачів: %d   Серверів: %d" %
              (cfg.get("host") or "(не вказано)", cfg["port"], len(users), len(cfg["peers"])))
        print(" 1) Запустити сервер у цьому вікні")
        print(" 2) Статус")
        print(" 3) Додати користувача (видає ключ підключення для застосунку)")
        print(" 4) Видалити користувача")
        print(" 5) Створити запрошення для іншого сервера")
        print(" 6) Підключити інший сервер (вставити його запрошення)")
        print(" 7) Видалити сервер зі списку")
        print(" 8) Налаштування (назва, адреса, порт)")
        print(" 9) Нікнейми (переглянути / звільнити)")
        print(" 0) Вихід")
        choice = ask("Вибір: ")
        try:
            if choice == "1":
                print("Сервер працює. Зупинити: Ctrl+C.")
                try:
                    asyncio.run(Server(store).serve())
                except KeyboardInterrupt:
                    print("\nСервер зупинено.")
            elif choice == "2":
                show_status(store)
            elif choice == "3":
                add_user(store)
            elif choice == "4":
                del_user(store)
            elif choice == "5":
                make_invite(store)
            elif choice == "6":
                add_peer(store)
            elif choice == "7":
                del_peer(store)
            elif choice == "8":
                settings(store)
            elif choice == "9":
                manage_names(store)
            elif choice == "0":
                return
        except KeyboardInterrupt:
            print()


def show_status(store):
    cfg, users = store.load_cfg(), store.load_users()
    st = Store._load(store.status_path, {})
    alive = st and now() - st.get("time", 0) < 20
    print("\nСервер зараз: %s" % ("ПРАЦЮЄ" if alive else "не запущений"))
    print("Відбиток сертифіката: %s" % store.fingerprint())
    print("Користувачі:")
    for uid, rec in users.items():
        mark = "у мережі" if alive and uid in st.get("online", []) else "—"
        print("  %s  (%s)  %s" % (uid, rec.get("name", ""), mark))
    print("Сервери:")
    for p in cfg["peers"]:
        mark = "зв'язок є" if alive and p["server_id"] in st.get("links", []) else "—"
        print("  %s  «%s»  %s:%s  %s" % (p["server_id"], p.get("name", ""), p.get("host") or "?", p.get("port"), mark))
    print("Запрошення, що очікують: %d" % len(cfg["invites"]))


def add_user(store):
    cfg = store.load_cfg()
    if not need_host(store, cfg):
        return
    print("\nУ застосунку: Контакти -> [⚙] -> Сервер: там показано «Мій ID».")
    uid = ask("ID пристрою: ")
    if not ID_RE.match(uid):
        print("Невірний ID (6-32 символи: літери, цифри, - _).")
        return
    name = ask("Позивний (для списку, необов'язково): ")
    users = store.load_users()
    token = secrets.token_urlsafe(24)
    users[uid] = {"h": sha256(token), "name": name, "created": now()}
    store.save_users(users)
    key = "PDA1:" + b64e({"h": cfg["host"], "p": cfg["port"], "f": store.fingerprint(), "t": token})
    print("\nКлюч підключення (показується ОДИН раз, передай людині безпечно):\n")
    print(key)
    print("\nУ застосунку: Контакти -> [⚙] -> Сервер -> вставити ключ -> Підключити.")


def del_user(store):
    users = store.load_users()
    uid = ask("ID користувача для видалення: ")
    if uid in users:
        del users[uid]
        store.save_users(users)
        names = store.load_names()
        store.save_names({k: v for k, v in names.items() if v.get("uid") != uid})
        print("Видалено (діє протягом ~5 секунд, якщо сервер запущено).")
    else:
        print("Такого немає.")


def make_invite(store):
    cfg = store.load_cfg()
    if not need_host(store, cfg):
        return
    inv = {"id": secrets.token_hex(4), "secret": secrets.token_urlsafe(24), "created": now()}
    cfg["invites"].append(inv)
    store.save_cfg(cfg)
    code = "PDAS1:" + b64e({"i": cfg["server_id"], "n": cfg["name"], "h": cfg["host"], "p": cfg["port"],
                            "f": store.fingerprint(), "s": inv["secret"]})
    print("\nЗапрошення (одноразове, передай власнику іншого сервера безпечно):\n")
    print(code)
    print("\nВін вставляє його в пункті 6. Після підключення цей сервер дізнається про нього сам.")


def add_peer(store):
    code = ask("Встав запрошення (PDAS1:...): ")
    if not code.startswith("PDAS1:"):
        print("Це не запрошення сервера.")
        return
    try:
        inv = b64d(code[6:])
        cfg = store.load_cfg()
        peer = {"server_id": inv["i"], "name": inv.get("n", ""), "host": inv["h"], "port": int(inv["p"]),
                "fp": inv["f"], "secret": inv["s"]}
    except Exception:
        print("Запрошення пошкоджене.")
        return
    if peer["server_id"] == cfg["server_id"]:
        print("Це запрошення від цього ж сервера.")
        return
    cfg["peers"] = [p for p in cfg["peers"] if p["server_id"] != peer["server_id"]] + [peer]
    store.save_cfg(cfg)
    print("Сервер «%s» додано. Зв'язок встановиться сам (потрібен запущений сервер)." % peer["name"])
    if not cfg.get("host"):
        print("Порада: вкажи свою адресу (пункт 8), щоб той сервер теж міг сам підключатися до тебе.")


def del_peer(store):
    cfg = store.load_cfg()
    sid = ask("ID сервера для видалення: ")
    n = len(cfg["peers"])
    cfg["peers"] = [p for p in cfg["peers"] if p["server_id"] != sid]
    store.save_cfg(cfg)
    print("Видалено." if len(cfg["peers"]) < n else "Такого немає.")


def manage_names(store):
    names = store.load_names()
    if not names:
        print("\nЗайнятих нікнеймів поки немає.")
        return
    print("\nЗайняті нікнейми (на цьому сервері й у з'єднаній мережі):")
    for v in sorted(names.values(), key=lambda x: x["name"].casefold()):
        print("  %-24s  ID %s" % (v["name"], v["uid"]))
    cf = ask("Нік, який звільнити (Enter - нічого): ").casefold()
    if cf and cf in names:
        del names[cf]
        store.save_names(names)
        print("Звільнено на цьому сервері. На інших серверах мережі його може звільнити їхній адміністратор.")


def settings(store):
    cfg = store.load_cfg()
    print("\n1) Назва: %s\n2) Адреса (IP або домен): %s\n3) Порт: %s\n0) Назад" %
          (cfg["name"], cfg.get("host") or "(не вказано)", cfg["port"]))
    c = ask("Вибір: ")
    if c == "1":
        cfg["name"] = ask("Нова назва: ", cfg["name"])[:40]
    elif c == "2":
        cfg["host"] = ask("Адреса: ", cfg.get("host", ""))
    elif c == "3":
        p = ask("Порт: ", str(cfg["port"]))
        if p.isdigit() and 1 <= int(p) <= 65535:
            cfg["port"] = int(p)
    store.save_cfg(cfg)


def main():
    data = DATA
    args = sys.argv[1:]
    if "--data" in args:
        data = args[args.index("--data") + 1]
    store = Store(data)
    store.ensure_cert()
    if "--run" in args:
        try:
            asyncio.run(Server(store).serve())
        except KeyboardInterrupt:
            pass
    else:
        menu(store)


if __name__ == "__main__":
    main()

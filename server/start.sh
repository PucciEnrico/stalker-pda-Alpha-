#!/usr/bin/env bash
# S.T.A.L.K.E.R. PDA сервер: Linux. Запуск: ./start.sh   (або ./start.sh --run для запуску без меню)
cd "$(dirname "$0")"
if ! command -v python3 >/dev/null 2>&1; then
  echo "Не знайдено Python 3. Встанови: sudo apt install python3 python3-pip"
  exit 1
fi
if ! python3 -c "import cryptography" >/dev/null 2>&1; then
  echo "Встановлюю пакет cryptography..."
  python3 -m pip install --user cryptography 2>/dev/null \
    || python3 -m pip install --user --break-system-packages cryptography \
    || { echo "Не вдалося. Спробуй: sudo apt install python3-cryptography"; exit 1; }
fi
exec python3 pdaserver.py "$@"

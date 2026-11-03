@echo off
chcp 65001 >nul
rem S.T.A.L.K.E.R. PDA сервер: Windows. Двічі клацни, щоб відкрити меню.
cd /d "%~dp0"
where python >nul 2>nul
if errorlevel 1 (
  echo Не знайдено Python 3. Завантаж з python.org і постав галочку "Add Python to PATH".
  pause
  exit /b 1
)
python -c "import cryptography" >nul 2>nul
if errorlevel 1 (
  echo Встановлюю пакет cryptography...
  python -m pip install cryptography
)
python pdaserver.py %*
pause

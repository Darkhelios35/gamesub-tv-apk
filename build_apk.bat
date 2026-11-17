@echo off
chcp 65001 >nul
title GameSub TV - GitHub Bulut APK Oluşturucu
cd /d "%~dp0"

echo ====================================================================
echo   📺 GameSub TV — Otomatik GitHub Bulut APK Derleyici ve İndirici
echo ====================================================================
python github_apk_derle.py
pause

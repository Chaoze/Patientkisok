@echo off
setlocal EnableExtensions
cd /d "%~dp0"
title Patient Kiosk - Echter Kioskmodus

echo ============================================================
echo   PATIENT KIOSK - ECHTEN ANDROID KIOSKMODUS AKTIVIEREN
echo ============================================================
echo.
echo Dieses Skript macht Patient Kiosk zum Device Owner.
echo Danach koennen Patienten Home, Letzte Apps und andere Apps
icht mehr oeffnen, solange der Patientenmodus aktiv ist.
echo.
echo VORHER AUF DEM TABLET:
echo   1. Patient Kiosk APK muss installiert sein.
echo   2. Entwickleroptionen aktivieren.
echo   3. USB-Debugging einschalten.
echo   4. Tablet per USB mit diesem PC verbinden.
echo   5. Falls Android nach USB-Debugging fragt: ZULASSEN.
echo.
echo WICHTIG: Android erlaubt Device Owner nur auf einem dafuer
geeigneten/sauberen Geraet. Falls der Befehl wegen vorhandener
Konten oder bereits erfolgter Einrichtung abgelehnt wird, muessen
Google-/Herstellerkonten entfernt werden; bei manchen Geraeten ist
ein Werksreset erforderlich.
echo.
pause

if not exist "%~dp0platform-tools\adb.exe" (
    echo.
    echo Lade offizielle Google Android Platform Tools herunter...
    powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; Invoke-WebRequest -Uri 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile '%~dp0platform-tools.zip'; if (Test-Path '%~dp0platform-tools') { Remove-Item -Recurse -Force '%~dp0platform-tools' }; Expand-Archive -Path '%~dp0platform-tools.zip' -DestinationPath '%~dp0' -Force"
    if errorlevel 1 (
        echo.
        echo FEHLER: Platform Tools konnten nicht heruntergeladen werden.
        pause
        exit /b 1
    )
)

set "ADB=%~dp0platform-tools\adb.exe"

echo.
echo Starte ADB...
"%ADB%" start-server

echo.
echo Angeschlossene Android-Geraete:
"%ADB%" devices

echo.
echo Falls oben UNAUTHORIZED steht: Tablet entsperren, USB-Debugging
Echo zulassen und danach eine Taste druecken.
pause

"%ADB%" devices

echo.
echo ------------------------------------------------------------
echo Aktiviere Patient Kiosk als Device Owner...
echo ------------------------------------------------------------
"%ADB%" shell dpm set-device-owner ch.patientkiosk.app/.KioskDeviceAdminReceiver
set RESULT=%ERRORLEVEL%

echo.
if "%RESULT%"=="0" (
    echo ============================================================
    echo   ERFOLG
    echo ============================================================
    echo Patient Kiosk ist jetzt Device Owner.
    echo.
    echo Starte die App auf dem Tablet neu.
    echo Unten in der App sollte nun stehen:
    echo       SICHERER KIOSKMODUS
    echo.
    echo Danach testen: von unten hochwischen / Home / Letzte Apps.
    echo Diese Funktionen duerfen im Patientenmodus nicht mehr aus der
    echo App herausfuehren.
) else (
    echo ============================================================
    echo   DEVICE OWNER KONNTE NICHT AKTIVIERT WERDEN
    echo ============================================================
    echo Lies die Fehlermeldung direkt oberhalb.
    echo.
    echo Typische Ursache: Auf dem Tablet existiert bereits ein Konto
    echo oder das Geraet ist schon vollstaendig eingerichtet.
    echo Android empfiehlt fuer dedizierte Kioskgeraete einen sauberen
    echo bzw. werkseitig zurueckgesetzten Zustand fuer die Provisionierung.
)

echo.
pause
endlocal

Unicode true
!include "MUI2.nsh"
!ifndef APP_SOURCE
  !error "APP_SOURCE missing"
!endif
!ifndef OUT_FILE
  !error "OUT_FILE missing"
!endif
Name "Cubical Compare V5"
OutFile "${OUT_FILE}"
InstallDir "$LOCALAPPDATA\Programs\Cubical Compare V5"
RequestExecutionLevel user
ShowInstDetails show
ShowUninstDetails show
!define MUI_ABORTWARNING
!define MUI_ICON "..\CubicalCompareV5\Assets\CubicalCompare.ico"
!define MUI_UNICON "..\CubicalCompareV5\Assets\CubicalCompare.ico"
!define MUI_FINISHPAGE_RUN "$INSTDIR\CubicalCompareV5.exe"
!define MUI_FINISHPAGE_RUN_TEXT "Launch Cubical Compare V5"
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "English"
!insertmacro MUI_LANGUAGE "PortugueseBR"
!insertmacro MUI_LANGUAGE "Spanish"
!insertmacro MUI_LANGUAGE "French"
!insertmacro MUI_LANGUAGE "German"
!insertmacro MUI_LANGUAGE "Russian"
!insertmacro MUI_LANGUAGE "Japanese"
!insertmacro MUI_LANGUAGE "SimpChinese"
!insertmacro MUI_LANGUAGE "Italian"
Section "Cubical Compare V5" Main
 SetOutPath "$INSTDIR"
 File /r "${APP_SOURCE}\*.*"
 WriteUninstaller "$INSTDIR\Uninstall.exe"
 CreateDirectory "$SMPROGRAMS\Cubical Compare V5"
 CreateShortCut "$SMPROGRAMS\Cubical Compare V5\Cubical Compare V5.lnk" "$INSTDIR\CubicalCompareV5.exe"
 CreateShortCut "$SMPROGRAMS\Cubical Compare V5\Uninstall.lnk" "$INSTDIR\Uninstall.exe"
 WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\CubicalCompareV5" "DisplayName" "Cubical Compare V5"
 WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\CubicalCompareV5" "DisplayIcon" "$INSTDIR\CubicalCompareV5.exe"
 WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\CubicalCompareV5" "UninstallString" '"$INSTDIR\Uninstall.exe"'
 WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\CubicalCompareV5" "DisplayVersion" "5.0.0"
 WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\CubicalCompareV5" "Publisher" "RetroFrost"
SectionEnd
Section "Uninstall"
 Delete "$SMPROGRAMS\Cubical Compare V5\Cubical Compare V5.lnk"
 Delete "$SMPROGRAMS\Cubical Compare V5\Uninstall.lnk"
 RMDir "$SMPROGRAMS\Cubical Compare V5"
 RMDir /r "$INSTDIR"
 DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\CubicalCompareV5"
SectionEnd
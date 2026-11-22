@echo off
rem The integrated Gradle project is generated from the locked Kazumi source.
python "%~dp0integration\kazumi\build_android.py" --gradle %*
exit /b %errorlevel%

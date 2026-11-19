@echo off
java -Djava.library.path="%~dp0." -cp "%~dp0Clientboardrs485plugin-10.2.jar;%~dp0lib\RXTXcomm.jar" ru.apertum.cbrs485.test.TestRS
pause

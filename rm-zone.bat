cd /d "%~dp0"
for /r %%F in (*Zone.Identifier) do del /f /q "%%F"
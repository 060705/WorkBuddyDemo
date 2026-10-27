@echo off
chcp 65001 >nul
REM ============================================================
REM  AI Agent 项目一键停止脚本
REM  功能：结束主应用 + MCP Server 两个 Java 进程
REM  用法：双击运行，或命令行执行 stop.bat
REM ============================================================
setlocal enabledelayedexpansion

echo ============================================
echo  正在停止 AI Agent 服务...
echo ============================================

REM ---- 用 jps 精确定位两个 jar 进程，只杀本项目，不误伤 IDEA 等其他 Java ----
set "MAIN_PID="
set "MCP_PID="

for /f "tokens=1,*" %%a in ('jps -l 2^>nul') do (
    set "line=%%b"
    if "!line!"=="target/demo-0.0.6.jar" set "MAIN_PID=%%a"
    if "!line!"=="target/demo-0.0.6-mcp-server.jar" set "MCP_PID=%%a"
)

if not defined MAIN_PID if not defined MCP_PID (
    echo [提示] 未发现运行中的 AI Agent 进程，可能已停止。
    pause
    exit /b 0
)

if defined MAIN_PID (
    echo [停止] 主应用 PID=%MAIN_PID%
    taskkill /F /PID %MAIN_PID% >nul 2>&1
)
if defined MCP_PID (
    echo [停止] MCP Server PID=%MCP_PID%
    taskkill /F /PID %MCP_PID% >nul 2>&1
)

REM ---- 确认 8089 端口已释放 ----
timeout /t 1 /nobreak >nul
for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":8089" ^| findstr "LISTENING"') do (
    echo [警告] 端口 8089 仍被 PID %%p 占用
)
echo.
echo [完成] 服务已停止。
pause

@echo off
chcp 65001 >nul
REM ============================================================
REM  AI Agent 项目一键启动脚本
REM  功能：打包（可选）-> 启动主应用（自动拉起 MCP Server 子进程）
REM  用法：双击运行，或在命令行执行 start.bat [port]
REM        port 默认 8089，可传参覆盖，如 start.bat 8080
REM  Key 已写死在 src/main/resources/application.yaml，无需环境变量
REM ============================================================
setlocal enabledelayedexpansion

REM ---- 切到脚本所在目录（保证相对路径 target/ 正确）----
cd /d "%~dp0.."

set PORT=%~1
if "%PORT%"=="" set PORT=8089

echo ============================================
echo  正在启动 AI Agent 主应用（端口 %PORT%）
echo ============================================

REM ---- 若 jar 不存在，先打包 ----
if not exist "target\demo-0.0.6.jar" (
    echo [提示] 未找到 target\demo-0.0.6.jar，先执行打包...
    call mvn -s settings.xml clean package -DskipTests
    if errorlevel 1 (
        echo [错误] 打包失败，请检查 Maven 配置。
        pause
        exit /b 1
    )
)

echo [启动] java -jar target\demo-0.0.6.jar --server.port=%PORT%
echo [说明] 启动成功后访问 http://localhost:%PORT%
echo [说明] MCP 天气查询依赖 Clash 代理（127.0.0.1:7897），未开 Clash 时 /ai/mcp 会超时，其余功能不受影响
echo.

java -jar target\demo-0.0.6.jar --server.port=%PORT%

pause

@echo off
chcp 65001 >nul

:: Easy Daily Report - Windows 快速运行脚本
:: 自动加载 .env 文件并启动应用

setlocal enabledelayedexpansion

set "PROJECT_NAME=Easy Daily Report"
set "SCRIPT_DIR=%~dp0"

:: 颜色代码
set "BLUE=[36m"
set "GREEN=[32m"
set "YELLOW=[33m"
set "RED=[31m"
set "NC=[0m"

call :show_banner

:: 检查 Java
call :check_java
if errorlevel 1 exit /b 1

:: 加载 .env
call :load_env

:: 检查配置
call :check_config

:: 检查 PGVector（可选）
call :check_pgvector

:: 构建项目
call :build_if_needed

:: 运行应用
call :run_app

goto :eof

:: 打印标题
:show_banner
echo.
echo %BLUE%╔════════════════════════════════════════════════════════════╗%NC%
echo %BLUE%║              Easy Daily Report - 智能日报生成器            ║%NC%
echo %BLUE%╚════════════════════════════════════════════════════════════╝%NC%
echo.
goto :eof

:: 检查 Java
:check_java
java -version >nul 2>&1
if errorlevel 1 (
    echo %RED%[ERROR]%NC% Java 未安装，请先安装 JDK 21+
    exit /b 1
)
echo %BLUE%[INFO]%NC% Java 已安装
goto :eof

:: 加载 .env 文件
:load_env
if exist "%SCRIPT_DIR%.env" (
    echo %BLUE%[INFO]%NC% 加载环境变量: .env
    for /f "usebackq tokens=*" %%a in ("%SCRIPT_DIR%.env") do (
        set "line=%%a"
        if not "!line:~0,1!"=="#" (
            if not "!line!"=="" (
                for /f "tokens=1,2 delims==" %%b in ("%%a") do (
                    set "%%b=%%c"
                )
            )
        )
    )
    echo %GREEN%[SUCCESS]%NC% 环境变量加载完成
) else (
    echo %YELLOW%[WARN]%NC% .env 文件不存在，使用默认配置
    echo %YELLOW%[WARN]%NC% 提示: 复制 .env.example 为 .env 并配置你的 API Key
)
goto :eof

:: 检查关键配置
:check_config
if "%OPENAI_API_KEY%"=="" (
    echo %YELLOW%[WARN]%NC% OPENAI_API_KEY 未配置
    echo %YELLOW%[WARN]%NC% 请在 .env 文件中设置有效的 API Key
    echo.
    pause
)
goto :eof

:: 检查 PGVector
:check_pgvector
echo %BLUE%[INFO]%NC% 检查 PGVector 连接 (%PGVECTOR_HOST%:%PGVECTOR_PORT%)...
:: Windows 没有 nc 命令，这里仅作提示
if "%PGVECTOR_HOST%"=="" set PGVECTOR_HOST=localhost
if "%PGVECTOR_PORT%"=="" set PGVECTOR_PORT=5432
echo %YELLOW%[WARN]%NC% 请确保 PGVector 已启动: docker compose up -d
goto :eof

:: 构建项目
:build_if_needed
if not exist "%SCRIPT_DIR%build\libs\easy-daily-report-0.0.1-SNAPSHOT.jar" (
    echo %BLUE%[INFO]%NC% 首次运行，需要构建项目...
    call gradlew.bat bootJar -x test --quiet
    echo %GREEN%[SUCCESS]%NC% 构建完成
)
goto :eof

:: 运行应用
:run_app
echo.
echo %BLUE%[INFO]%NC% 启动 %PROJECT_NAME%...
echo %BLUE%[INFO]%NC% 按 Ctrl+C 停止应用
echo.
java -jar "%SCRIPT_DIR%build\libs\easy-daily-report-0.0.1-SNAPSHOT.jar"
goto :eof

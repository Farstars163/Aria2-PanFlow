$ErrorActionPreference = 'SilentlyContinue'
Write-Host '========================================'
Write-Host '  Aria2-PanFlow 进程关闭脚本 按端口关闭'
Write-Host '========================================'
Write-Host ''
Write-Host '正在关闭启动器进程...'
Stop-Process -Name 'Aria2PanFlow' -Force -ErrorAction SilentlyContinue
Write-Host '正在按端口关闭服务进程 18080 后端 / 16800 Aria2 / 9222 调试浏览器 / 15000-15001 网盘服务...'
Get-NetTCPConnection -LocalPort 18080,16800,9222,15000,15001 -State Listen -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty OwningProcess -Unique |
    ForEach-Object {
        Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue
        Write-Host ("  已关闭端口进程 PID " + $_)
    }
Write-Host ''
Write-Host '[OK] 全部进程已关闭'
Start-Sleep -Seconds 3

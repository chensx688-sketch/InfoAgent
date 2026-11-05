# 阶段 0 验收命令：./scripts/build-debug.ps1 [-PskipMcpHealth=true]
# 设置 JAVA_HOME/启用 JDK17 后调用 gradlew assembleDebug；产物见输出末尾。
$ErrorActionPreference = 'Stop'
$jdk = 'C:\Users\a\.jdks\jdk-17'
if (-not (Test-Path "$jdk\bin\java.exe")) { throw "JDK not found at $jdk (阶段0工具链未就绪)" }
$env:JAVA_HOME = $jdk
$env:Path = "$jdk\bin;" + $env:Path
$env:GRADLE_OPTS = "-Djava.net.preferIPv4Stack=true -Dfile.encoding=UTF-8"
# 经 ASCII 联接路径构建（AGP 测试工具链不支持中文路径；D:\InfoAgent → D:\信息小agent）
Set-Location 'D:\InfoAgent'
& 'D:\InfoAgent\gradlew.bat' assembleDebug @args
exit $LASTEXITCODE
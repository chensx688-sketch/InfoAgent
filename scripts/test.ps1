# 阶段 2+ 验收命令：JVM 单测 + 出包
$ErrorActionPreference = 'Stop'
$jdk = 'C:\Users\a\.jdks\jdk-17'
if (-not (Test-Path "$jdk\bin\java.exe")) { throw "JDK not found at $jdk (阶段0工具链未就绪)" }
$env:JAVA_HOME = $jdk
$env:Path = "$jdk\bin;" + $env:Path
$env:GRADLE_OPTS = "-Djava.net.preferIPv4Stack=true -Dfile.encoding=UTF-8"
Set-Location 'D:\InfoAgent'
& 'D:\InfoAgent\gradlew.bat' testDebugUnitTest assembleDebug @args
exit $LASTEXITCODE
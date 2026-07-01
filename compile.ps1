cd D:\project\mini-agent-springboot
mvn clean compile 2>&1 | Out-File -FilePath D:\project\mini-agent-springboot\build_output.txt -Encoding UTF8
Write-Host "Build output saved to build_output.txt"

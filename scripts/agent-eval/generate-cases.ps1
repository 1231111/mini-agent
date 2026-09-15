param(
    [string]$RunId = "",
    [int]$PerCategory = 10
)

$ErrorActionPreference = "Stop"
$EvalDir = $PSScriptRoot
if ($RunId -eq "") {
    $RunId = "gen-" + (Get-Date -Format "yyyyMMdd-HHmmss")
}

$PromptDir = Join-Path $EvalDir "prompts" $RunId
New-Item -ItemType Directory -Force -Path $PromptDir | Out-Null

$tag = $RunId.Replace("-", "").Replace("_", "")
if ($tag.Length -gt 12) { $tag = $tag.Substring($tag.Length - 12) }

function New-Case($id, $category, $tier, $prompt, $timeoutSec, $sloMs, $checks) {
    $file = "$id.txt"
    [IO.File]::WriteAllText((Join-Path $PromptDir $file), $prompt.Trim(), [Text.UTF8Encoding]::new($false))
    return [ordered]@{
        id         = $id
        category   = $category
        tier       = $tier
        promptFile = "$RunId/$file"
        timeoutSec = $timeoutSec
        sloMs      = $sloMs
        checks     = $checks
    }
}

$cases = @()

$cases += New-Case "QA01" "qa" "smoke" "9 乘以 7 等于多少？只回答数字，不要解释。" 120 20000 @(
    @{ type = "status_done" }, @{ type = "no_planner" },
    @{ type = "response_contains"; value = "63" },
    @{ type = "response_not_contains"; value = "6363" }
)

$cases += New-Case "QA02" "qa" "smoke" "把「zebra、apple、mango」按字母顺序排列，只输出排序后的英文单词，逗号分隔，不要其它文字。" 120 25000 @(
    @{ type = "status_done" }, @{ type = "no_planner" },
    @{ type = "response_contains"; value = "apple" },
    @{ type = "response_contains"; value = "mango" },
    @{ type = "response_contains"; value = "zebra" }
)

$cases += New-Case "QA03" "qa" "regression" "HTTP 状态码 404 表示什么？用一句中文回答，不超过 30 字。" 120 30000 @(
    @{ type = "status_done" }, @{ type = "no_planner" },
    @{ type = "response_min_length"; number = 4 },
    @{ type = "response_regex"; value = "未找到|不存在|找不到|Not Found|404" }
)

$cases += New-Case "QA04" "qa" "regression" "JSON 里布尔类型有哪两个字面量？只输出这两个词，用逗号分隔。" 120 25000 @(
    @{ type = "status_done" }, @{ type = "no_planner" },
    @{ type = "response_contains"; value = "true" },
    @{ type = "response_contains"; value = "false" }
)

$cases += New-Case "QA05" "qa" "regression" "1 公里等于多少米？只回答数字。" 120 20000 @(
    @{ type = "status_done" }, @{ type = "no_planner" },
    @{ type = "response_contains"; value = "1000" }
)

$cases += New-Case "QA06" "qa" "regression" "十六进制 0xFF 等于十进制多少？只回答数字。" 120 20000 @(
    @{ type = "status_done" }, @{ type = "no_planner" },
    @{ type = "response_contains"; value = "255" }
)

$cases += New-Case "QA07" "qa" "regression" "闰年是指能被 4 整除的年份吗？只回答「是」或「否」一个字。" 120 25000 @(
    @{ type = "status_done" }, @{ type = "no_planner" },
    @{ type = "response_regex"; value = "否|不是|不完全|还|也" }
)

$cases += New-Case "QA08" "qa" "regression" "把字符串 HELLO 转成小写，只输出结果。" 120 20000 @(
    @{ type = "status_done" }, @{ type = "no_planner" },
    @{ type = "response_contains"; value = "hello" }
)

$cases += New-Case "QA09" "qa" "regression" "2 的 10 次方是多少？只回答数字。" 120 20000 @(
    @{ type = "status_done" }, @{ type = "no_planner" },
    @{ type = "response_contains"; value = "1024" }
)

$cases += New-Case "QA10" "qa" "regression" "TCP 和 UDP 哪个是面向连接的？只回答 TCP 或 UDP。" 120 25000 @(
    @{ type = "status_done" }, @{ type = "no_planner" },
    @{ type = "response_contains"; value = "TCP" }
)

$cases += New-Case "SIM01" "simple" "smoke" "请在工作区写入文件 t${tag}_sim01.txt，内容恰好一行：GEN-$tag-SIM01。写完后只回复文件路径。" 300 120000 @(
    @{ type = "status_done" }, @{ type = "no_planner" },
    @{ type = "workspace_file_exists"; glob = "t${tag}_sim01.txt" },
    @{ type = "workspace_file_contains"; glob = "t${tag}_sim01.txt"; value = "GEN-$tag-SIM01" }
)

$cases += New-Case "SIM02" "simple" "smoke" "请写入 Java 文件 t${tag}_Hello.java，类名 Hello，main 方法打印 GEN-$tag-SIM02。不要编译。" 600 180000 @(
    @{ type = "status_done" },
    @{ type = "workspace_file_exists"; glob = "t${tag}_Hello.java" },
    @{ type = "workspace_file_contains"; glob = "t${tag}_Hello.java"; value = "GEN-$tag-SIM02" }
)

$cases += New-Case "SIM03" "simple" "regression" "请写入 Python 文件 t${tag}_calc.py，定义 add(a,b) 返回两数之和，并含一行注释 GEN-$tag-SIM03。" 600 180000 @(
    @{ type = "status_done" },
    @{ type = "workspace_file_exists"; glob = "t${tag}_calc.py" },
    @{ type = "workspace_file_contains"; glob = "t${tag}_calc.py"; value = "add(" }
)

$cases += New-Case "SIM04" "simple" "regression" "请写入 JSON 文件 t${tag}_config.json，内容为 run=$tag version=1 ok=true 的 JSON，格式化缩进 2 空格。" 300 120000 @(
    @{ type = "status_done" },
    @{ type = "workspace_file_exists"; glob = "t${tag}_config.json" },
    @{ type = "workspace_file_contains"; glob = "t${tag}_config.json"; value = "version" }
)

$cases += New-Case "SIM05" "simple" "regression" "请用 write_xlsx 生成 t${tag}_scores.xlsx，含表头 Name,Score 和一行 Alice,95。不要其它 sheet。" 600 180000 @(
    @{ type = "status_done" },
    @{ type = "workspace_file_exists"; glob = "t${tag}_scores.xlsx" },
    @{ type = "workspace_file_min_length"; glob = "t${tag}_scores.xlsx"; number = 1000 }
)

$cases += New-Case "SIM06" "simple" "regression" "请用 write_docx 生成 t${tag}_note.docx，正文至少一行：GEN-$tag-SIM06。" 600 180000 @(
    @{ type = "status_done" },
    @{ type = "workspace_file_exists"; glob = "t${tag}_note.docx" },
    @{ type = "workspace_file_min_length"; glob = "t${tag}_note.docx"; number = 2000 }
)

$cases += New-Case "SIM07" "simple" "regression" "请写入 Markdown 文件 t${tag}_readme.md，含一级标题 GEN-$tag-SIM07 和至少 3 条无序列表。" 300 120000 @(
    @{ type = "status_done" },
    @{ type = "workspace_file_exists"; glob = "t${tag}_readme.md" },
    @{ type = "workspace_file_contains"; glob = "t${tag}_readme.md"; value = "GEN-$tag-SIM07" },
    @{ type = "workspace_file_min_lines"; glob = "t${tag}_readme.md"; number = 4 }
)

$cases += New-Case "SIM08" "simple" "regression" "请写入 t${tag}_greet.sh，内容为 echo GEN-$tag-SIM08，并确保文件以换行结尾。" 300 120000 @(
    @{ type = "status_done" },
    @{ type = "workspace_file_exists"; glob = "t${tag}_greet.sh" },
    @{ type = "workspace_file_contains"; glob = "t${tag}_greet.sh"; value = "GEN-$tag-SIM08" }
)

$cases += New-Case "SIM09" "simple" "regression" "请用 web_search 搜索 OpenJDK 最新 LTS 版本号，在回复中用一句话写出你找到的版本号，不要写文件。" 600 240000 @(
    @{ type = "status_done" },
    @{ type = "response_min_length"; number = 5 },
    @{ type = "response_regex"; value = "17|21|25|LTS|JDK" }
)

$cases += New-Case "SIM10" "simple" "regression" "请写入 Mermaid 文件 t${tag}_flow.mmd，内容为 flowchart LR  A[Start]-->B[GEN-$tag-SIM10]。" 300 120000 @(
    @{ type = "status_done" },
    @{ type = "workspace_file_exists"; glob = "t${tag}_flow.mmd" },
    @{ type = "workspace_file_contains"; glob = "t${tag}_flow.mmd"; value = "flowchart" }
)

$cases += New-Case "CPL01" "complex" "smoke" "请搜索近期 AI 行业一条简讯（可用 web_search），整理成 Markdown 文件 t${tag}_ai_news.md：含标题、日期、3 条要点，至少 8 行。" 1200 600000 @(
    @{ type = "status_done" },
    @{ type = "workspace_file_exists"; glob = "t${tag}_ai_news.md" },
    @{ type = "workspace_file_min_lines"; glob = "t${tag}_ai_news.md"; number = 6 }
)

$cases += New-Case "CPL02" "complex" "smoke" "请在工作区创建最小 Flask 示例：t${tag}_app.py（/ 返回 Hello $tag）和 t${tag}_requirements.txt（含 Flask）。两个文件都要写。" 1200 600000 @(
    @{ type = "status_done" },
    @{ type = "workspace_file_exists"; glob = "t${tag}_app.py" },
    @{ type = "workspace_file_exists"; glob = "t${tag}_requirements.txt" },
    @{ type = "workspace_file_contains"; glob = "t${tag}_app.py"; value = "Hello" }
)

$cases += New-Case "CPL03" "complex" "regression" "请用 web_extract 或 http_get 获取 https://openjdk.org/ 页面信息，将站点标题或首段说明写入 t${tag}_openjdk.md（至少 20 字）。不要开浏览器。" 1200 600000 @(
    @{ type = "status_done" },
    @{ type = "workspace_file_exists"; glob = "t${tag}_openjdk.md" },
    @{ type = "workspace_file_min_length"; glob = "t${tag}_openjdk.md"; number = 20 }
)

$cases += New-Case "CPL04" "complex" "regression" "请绘制 Mini Agent 三层架构（UI/App/LLM）Mermaid 源码 t${tag}_arch.mmd，并用 render_diagram 导出 t${tag}_arch.png。" 1200 600000 @(
    @{ type = "status_done" },
    @{ type = "workspace_file_exists"; glob = "t${tag}_arch.mmd" },
    @{ type = "workspace_file_exists"; glob = "t${tag}_arch.png" }
)

$cases += New-Case "CPL05" "complex" "regression" "请创建三文件 Python 小项目：t${tag}_main.py 调用 t${tag}_utils.py 的 greet()，以及 t${tag}_README.md 说明如何运行。" 1200 600000 @(
    @{ type = "status_done" },
    @{ type = "workspace_file_exists"; glob = "t${tag}_main.py" },
    @{ type = "workspace_file_exists"; glob = "t${tag}_utils.py" },
    @{ type = "workspace_file_exists"; glob = "t${tag}_README.md" }
)

$cases += New-Case "CPL06" "complex" "regression" "请写一份 t${tag}_spring_report.md：含二级标题「Spring Boot 简介」和至少 3 段正文，总字数不少于 200 字。" 1200 600000 @(
    @{ type = "status_done" },
    @{ type = "workspace_file_exists"; glob = "t${tag}_spring_report.md" },
    @{ type = "workspace_file_contains"; glob = "t${tag}_spring_report.md"; value = "Spring Boot" },
    @{ type = "workspace_file_min_length"; glob = "t${tag}_spring_report.md"; number = 200 }
)

$cases += New-Case "CPL07" "complex" "regression" "请生成 HTML 文件 t${tag}_page.html（含 h1 GEN-$tag-CPL07），再用 write_document 转为 t${tag}_page.docx。" 1200 600000 @(
    @{ type = "status_done" },
    @{ type = "workspace_file_exists"; glob = "t${tag}_page.html" },
    @{ type = "workspace_file_exists"; glob = "t${tag}_page.docx" }
)

$cases += New-Case "CPL08" "complex" "regression" "请写 t${tag}_api_design.md：描述 REST 用户 CRUD 四个接口（路径+方法），每个接口 1 行，共至少 4 节。" 1200 600000 @(
    @{ type = "status_done" },
    @{ type = "workspace_file_exists"; glob = "t${tag}_api_design.md" },
    @{ type = "workspace_file_min_lines"; glob = "t${tag}_api_design.md"; number = 8 }
)

$cases += New-Case "CPL09" "complex" "regression" "请创建 t${tag}_data.csv（3 列 id,name,score 共 3 行数据）和 t${tag}_data_summary.md（汇总 score 平均值）。" 1200 600000 @(
    @{ type = "status_done" },
    @{ type = "workspace_file_exists"; glob = "t${tag}_data.csv" },
    @{ type = "workspace_file_exists"; glob = "t${tag}_data_summary.md" }
)

$cases += New-Case "CPL10" "complex" "regression" "请写 t${tag}_checklist.md：含 5 项部署检查清单（编号列表），并创建 t${tag}_deploy.env 含 APP_NAME=$tag 一行。" 1200 600000 @(
    @{ type = "status_done" },
    @{ type = "workspace_file_exists"; glob = "t${tag}_checklist.md" },
    @{ type = "workspace_file_exists"; glob = "t${tag}_deploy.env" },
    @{ type = "workspace_file_contains"; glob = "t${tag}_deploy.env"; value = "APP_NAME=$tag" }
)

if ($cases.Count -ne ($PerCategory * 3)) {
    throw "Expected $($PerCategory * 3) cases, got $($cases.Count)"
}

$casesPath = Join-Path $EvalDir "cases-$RunId.json"
$cases | ConvertTo-Json -Depth 6 | Set-Content -Path $casesPath -Encoding UTF8

Write-Host "Generated RunId=$RunId cases=$($cases.Count)"
Write-Host "CasesFile=$casesPath"
Write-Host "PromptDir=$PromptDir"

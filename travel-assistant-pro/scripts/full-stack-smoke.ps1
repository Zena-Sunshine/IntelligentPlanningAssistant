$ErrorActionPreference = "Stop"

$businessBase = "http://127.0.0.1:8081"
$agentBase = "http://127.0.0.1:8001"
$webBase = "http://127.0.0.1:5173"
$internalHeaders = @{ "X-Internal-Service-Key" = "voyageiq-local-internal-key" }

function Read-SseEvents([string]$Content) {
    $events = @()
    foreach ($line in ($Content -split "`r?`n")) {
        if ($line.StartsWith("data:")) {
            $events += ($line.Substring(5).Trim() | ConvertFrom-Json)
        }
    }
    return $events
}

function Send-Stream([string]$ConversationId, [hashtable]$Headers, [string]$Content) {
    $requestId = "full-stack-" + [guid]::NewGuid().ToString()
    $response = Invoke-WebRequest -Method Post `
        -Uri "$businessBase/api/v1/conversations/$ConversationId/messages:stream" `
        -Headers $Headers -ContentType "application/json" -TimeoutSec 60 `
        -Body (@{ content = $Content; requestId = $requestId } | ConvertTo-Json)
    return [pscustomobject]@{
        requestId = $requestId
        statusCode = $response.StatusCode
        events = @(Read-SseEvents $response.Content)
    }
}

$health = [ordered]@{
    web = (Invoke-WebRequest -UseBasicParsing "$webBase/" -TimeoutSec 5).StatusCode
    agent = (Invoke-WebRequest -UseBasicParsing "$agentBase/health" -TimeoutSec 5).StatusCode
    business = (Invoke-WebRequest -UseBasicParsing "$businessBase/actuator/health" -TimeoutSec 5).StatusCode
}

$login = Invoke-RestMethod -Method Post -Uri "$businessBase/api/v1/auth/login" `
    -ContentType "application/json" -Body '{"username":"voyage","password":"Voyage@2026"}'
$headers = @{ Authorization = "Bearer $($login.accessToken)" }
$conversation = Invoke-RestMethod -Method Post -Uri "$businessBase/api/v1/conversations" `
    -Headers $headers -ContentType "application/json" -Body '{"title":"自动化全栈回归"}'
$approvalBefore = Invoke-RestMethod -Method Get -Uri "$businessBase/internal/v1/approvals?userId=$($login.user.id)&tenantId=$($login.user.tenantId)" `
    -Headers $internalHeaders

$negative = Send-Stream $conversation.id $headers "不要提交出差申请，只查审批进度"
$hotel = Send-Stream $conversation.id $headers "帮我找一家上海的酒店"
$followUp = Send-Stream $conversation.id $headers "那它的价格怎么样？"
$wuhan = Send-Stream $conversation.id $headers "武汉天气"
$general = Send-Stream $conversation.id $headers "你是谁"

$negativeRoute = @($negative.events | Where-Object { $_.type -eq "route" })[0]
$followUpRoute = @($followUp.events | Where-Object { $_.type -eq "route" })[0]
$negativeDone = @($negative.events | Where-Object { $_.type -eq "done" })[0]
$followUpDone = @($followUp.events | Where-Object { $_.type -eq "done" })[0]
$wuhanRoute = @($wuhan.events | Where-Object { $_.type -eq "route" })[0]
$wuhanCard = @($wuhan.events | Where-Object { $_.type -eq "card" })[0]
$wuhanDone = @($wuhan.events | Where-Object { $_.type -eq "done" })[0]
$wuhanTools = @($wuhan.events | Where-Object { $_.type -eq "tool_end" })
$generalSession = @($general.events | Where-Object { $_.type -eq "session" })[0]
$generalThinkingStart = @($general.events | Where-Object { $_.type -eq "thinking_start" })[0]
$generalCompositionStart = @($general.events | Where-Object { $_.type -eq "composition_start" })[0]
$generalTextDeltas = @($general.events | Where-Object { $_.type -eq "text" })
$generalPlan = @($general.events | Where-Object { $_.type -eq "plan" })[0]
$generalComposition = @($general.events | Where-Object { $_.type -eq "composition" })[0]
$generalDone = @($general.events | Where-Object { $_.type -eq "done" })[0]
$approvalAfter = Invoke-RestMethod -Method Get -Uri "$businessBase/internal/v1/approvals?userId=$($login.user.id)&tenantId=$($login.user.tenantId)" `
    -Headers $internalHeaders
$messageResponse = Invoke-WebRequest -Method Get -Uri "$businessBase/api/v1/conversations/$($conversation.id)/messages" -Headers $headers
$messages = $messageResponse.Content | ConvertFrom-Json
$messageCount = $messages.Count
$runtimeMessages = @($messages | Where-Object { $_.role -eq "assistant" -and $_.runtimeJson })

$assertions = [ordered]@{
    allServicesHealthy = @($health.Values | Where-Object { $_ -ne 200 }).Count -eq 0
    allStreamsHttp200 = @($negative.statusCode, $hotel.statusCode, $followUp.statusCode, $wuhan.statusCode, $general.statusCode | Where-Object { $_ -ne 200 }).Count -eq 0
    approvalCreateBlocked = "approval_create" -notin @($negativeRoute.data.intents)
    approvalStatusPreserved = "approval_status" -in @($negativeRoute.data.intents)
    approvalCountUnchanged = $approvalBefore.total -eq $approvalAfter.total
    internalApprovalToolSucceeded = "approval_status" -in @($negativeDone.data.successfulIntents)
    contextualFollowUpLane = $followUpRoute.data.lane -eq "context"
    contextualTravelIntent = "travel_search" -in @($followUpRoute.data.intents)
    contextualAgentSucceeded = "travel_search" -in @($followUpDone.data.successfulIntents)
    currentCityOverridesHistory = $wuhanRoute.data.slots.destination -eq "武汉"
    weatherCardUsesCurrentCity = $wuhanCard.data.card.data.city -eq "武汉"
    weatherAnswerUsesCurrentCity = $wuhanDone.data.answer -like "*武汉*"
    currentTurnDoesNotReplayHotelTool = $wuhanTools.Count -eq 1 -and $wuhanTools[0].data.toolName -eq "weather_query"
    generalAnswerIsQuerySpecific = $generalDone.data.answer -notlike "*当前信息还不足*"
    onlineModelActive = $generalSession.data.provider -ne "offline" -and $generalSession.data.model -ne "offline"
    modelAnalysisPhaseEmitted = $null -ne $generalThinkingStart
    responseGenerationPhaseEmitted = $null -ne $generalCompositionStart
    realModelTextStreamed = $generalTextDeltas.Count -gt 1
    publicExecutionPlanEmitted = $null -ne $generalPlan
    compositionSummaryEmitted = $null -ne $generalComposition
    runtimeDetailsPersisted = $runtimeMessages.Count -eq 5
    tenMessagesPersisted = $messageCount -eq 10
}

$report = [ordered]@{
    status = if (@($assertions.Values | Where-Object { -not $_ }).Count -eq 0) { "PASS" } else { "FAIL" }
    generatedAt = [DateTimeOffset]::UtcNow.ToString("o")
    environment = "local-real-http-chain"
    health = $health
    conversationId = $conversation.id
    approvalCount = @{ before = $approvalBefore.total; after = $approvalAfter.total }
    negativeApprovalRoute = @{ lane = $negativeRoute.data.lane; intents = @($negativeRoute.data.intents); successfulIntents = @($negativeDone.data.successfulIntents) }
    contextualFollowUpRoute = @{ lane = $followUpRoute.data.lane; intents = @($followUpRoute.data.intents); successfulIntents = @($followUpDone.data.successfulIntents) }
    currentCityOverride = @{ destination = $wuhanRoute.data.slots.destination; cardCity = $wuhanCard.data.card.data.city; answer = $wuhanDone.data.answer }
    generalAnswer = $generalDone.data.answer
    modelRuntime = @{ provider = $generalSession.data.provider; model = $generalSession.data.model; textDeltas = $generalTextDeltas.Count }
    persistedRuntimeMessages = $runtimeMessages.Count
    persistedMessages = $messageCount
    assertions = $assertions
    cleanup = "conversation soft-deleted after evidence collection"
}

$reportDir = Join-Path (Split-Path -Parent $PSScriptRoot) "docs\reports"
New-Item -ItemType Directory -Force -Path $reportDir | Out-Null
$reportPath = Join-Path $reportDir "full-stack-http-latest.json"
$report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $reportPath -Encoding utf8

Invoke-RestMethod -Method Delete -Uri "$businessBase/api/v1/conversations/$($conversation.id)" -Headers $headers | Out-Null

if ($report.status -ne "PASS") {
    throw "Full-stack smoke failed. See $reportPath"
}
$report | ConvertTo-Json -Depth 8

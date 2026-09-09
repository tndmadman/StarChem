$ErrorActionPreference = 'Stop'

$gradleArgs = @('-I', 'gradle/render-performance.gradle', 'validateRenderPerformance', '--no-daemon')
foreach ($arg in $args) {
    if ($arg -eq '--enforce-timing') {
        $gradleArgs += '-PrenderPerfEnforceTiming=true'
    } elseif ($arg.StartsWith('--baseline=')) {
        $gradleArgs += '-PrenderPerfBaseline=' + $arg.Substring('--baseline='.Length)
    } elseif ($arg.StartsWith('--max-regression=')) {
        $gradleArgs += '-PrenderPerfMaxRegression=' + $arg.Substring('--max-regression='.Length)
    } else {
        throw "Unknown render-performance argument: $arg"
    }
}

& .\gradlew.bat @gradleArgs
exit $LASTEXITCODE

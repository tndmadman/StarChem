$ErrorActionPreference = 'Stop'

& .\gradlew.bat classes
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$classPath = 'build\classes\java\main;build\resources\main'
$validatorArgs = @('--output=build\reports\render-performance.csv') + $args
& java '-Djava.awt.headless=true' -cp $classPath 'com.tndmadman.rts.RenderPerformanceValidator' @validatorArgs
exit $LASTEXITCODE

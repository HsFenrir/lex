[CmdletBinding()]
param(
    [switch]$Online
)

$ErrorActionPreference = 'Stop'
# Preserve the native exit code in PowerShell 7 as well as Windows PowerShell.
$PSNativeCommandUseErrorActionPreference = $false
$raiz = Split-Path -Parent $PSScriptRoot
$codigoSaida = 1

try {
    $sobrescritas = @(Get-ChildItem Env: | Where-Object Name -Match (
        '^(SPRING_.*|PG.*|TEST_DB_(URL|USERNAME|PASSWORD)|JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|_JAVA_OPTIONS|' +
        'MAVEN_(ARGS|OPTS|BASEDIR|PROJECTBASEDIR|USER_HOME)|MVNW_.*)$'
    ))
    if ($sobrescritas.Count -gt 0) {
        throw "Abra uma sessao sem estas variaveis: $($sobrescritas.Name -join ', ')."
    }

    Push-Location -LiteralPath $raiz
    try {
        $arquivosMaven = @('.mvn/jvm.config', '.mvn/maven.config', '.mvn/extensions.xml') |
            Where-Object { Test-Path -LiteralPath $_ }
        $arquivosSpring = @(Get-ChildItem -LiteralPath . -File -Force)
        if (Test-Path -LiteralPath config -PathType Container) {
            $arquivosSpring += @(Get-ChildItem -LiteralPath config -Recurse -File -Force)
        }
        $arquivosSpring = $arquivosSpring |
            Where-Object Name -Match '^application(?:-.*)?\.(properties|ya?ml)$'
        if ($arquivosMaven -or $arquivosSpring) {
            throw 'Revise a configuracao externa do Maven ou do Spring antes de rodar a suite.'
        }

        $settingsMaven = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.m2/settings.xml'
        if (Test-Path -LiteralPath $settingsMaven -PathType Leaf) {
            $settings = [xml](Get-Content -LiteralPath $settingsMaven -Raw)
            $propriedades = $settings.SelectNodes("//*[local-name()='profile']/*[local-name()='properties']/*")
            if ($propriedades | Where-Object LocalName -Match '^(spring\.|surefire\.|argLine$|maven\.test\.)') {
                throw 'Revise as propriedades de teste nos perfis do settings.xml do Maven.'
            }
        }

        $psql = 'C:\Program Files\PostgreSQL\18\bin\psql.exe'
        if (-not (Test-Path -LiteralPath $psql -PathType Leaf)) {
            $psql = (Get-Command psql.exe -CommandType Application).Source
        }
        $senha = Read-Host 'Senha de lex_test_runner' -AsSecureString
        $skipRcOriginal = $env:MAVEN_SKIP_RC
        try {
            $credencial = [pscredential]::new('lex_test_runner', $senha)
            $env:PGPASSWORD = $credencial.GetNetworkCredential().Password
            try {
                $identidade = & $psql -X -w -h 127.0.0.1 -p 5432 -d lex_test -U lex_test_runner `
                    -v ON_ERROR_STOP=1 -At -c "SELECT current_database(), current_user, inet_server_port(), has_database_privilege(current_user, 'lex_dev', 'CONNECT');"
                $codigoPsql = $LASTEXITCODE
            } finally {
                Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
            }

            if ($codigoPsql -ne 0 -or @($identidade).Count -ne 1 -or
                $identidade -cne 'lex_test|lex_test_runner|5432|f') {
                throw 'O destino, a identidade ou o isolamento do PostgreSQL diverge do esperado.'
            }

            $env:TEST_DB_URL = 'jdbc:postgresql://127.0.0.1:5432/lex_test'
            $env:TEST_DB_USERNAME = 'lex_test_runner'
            $env:TEST_DB_PASSWORD = $credencial.GetNetworkCredential().Password
            $env:MAVEN_SKIP_RC = '1'
            $argumentosMaven = @('clean', 'test')
            if (-not $Online) {
                $argumentosMaven = @('-o') + $argumentosMaven
            }
            & .\mvnw.cmd @argumentosMaven
            $codigoSaida = $LASTEXITCODE
        } finally {
            Remove-Item Env:TEST_DB_URL, Env:TEST_DB_USERNAME, Env:TEST_DB_PASSWORD -ErrorAction SilentlyContinue
            $env:MAVEN_SKIP_RC = $skipRcOriginal
            $senha.Dispose()
        }
    } finally {
        Pop-Location
    }
} catch {
    Write-Error $_.Exception.Message -ErrorAction Continue
}

exit $codigoSaida

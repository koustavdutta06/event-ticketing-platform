# Builds all six service images straight into Minikube's Docker daemon so the
# cluster can use them via imagePullPolicy: IfNotPresent, with no registry push needed.
#
# Usage (from repo root or anywhere):
#   .\k8s\build-images.ps1

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot

Write-Host "Pointing this shell's Docker CLI at Minikube's Docker daemon..." -ForegroundColor Cyan
& minikube -p minikube docker-env --shell powershell | Invoke-Expression

$services = @(
    "catalog-service",
    "inventory-service",
    "booking-service",
    "auth-service",
    "payment-service",
    "notification-service"
)

foreach ($service in $services) {
    $tag = "ticketing/${service}:latest"
    Write-Host "`nBuilding $tag ..." -ForegroundColor Cyan
    docker build -f "$repoRoot\$service\Dockerfile" -t $tag $repoRoot
    if ($LASTEXITCODE -ne 0) {
        throw "Build failed for $service"
    }
}

Write-Host "`nAll images built inside Minikube's Docker daemon:" -ForegroundColor Green
docker images | Select-String "ticketing/"

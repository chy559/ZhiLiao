$REGISTRY = "crpi-r3qkhh5h65pj3yag.cn-shanghai.personal.cr.aliyuncs.com"
$NAMESPACE = "zhiliao123"
$VERSION = "latest"

if ($args.Count -ge 1) {
    $VERSION = $args[0]
}

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  PaiSmart Docker Build & Push Script" -ForegroundColor Cyan
Write-Host "  Registry : $REGISTRY/$NAMESPACE" -ForegroundColor Cyan
Write-Host "  Version  : $VERSION" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

Write-Host "`n[1/3] Logging in to Alibaba Cloud Container Registry..." -ForegroundColor Yellow
docker login $REGISTRY

if ($LASTEXITCODE -ne 0) {
    Write-Host "Login failed! Please check your credentials." -ForegroundColor Red
    exit 1
}

Write-Host "`n[2/3] Building backend image..." -ForegroundColor Yellow
docker build -t "${REGISTRY}/${NAMESPACE}/backend:${VERSION}" -f Dockerfile .

if ($LASTEXITCODE -ne 0) {
    Write-Host "Backend build failed!" -ForegroundColor Red
    exit 1
}

Write-Host "`n[3/3] Pushing backend image to Alibaba Cloud..." -ForegroundColor Yellow
docker push "${REGISTRY}/${NAMESPACE}/backend:${VERSION}"

if ($LASTEXITCODE -ne 0) {
    Write-Host "Push failed!" -ForegroundColor Red
    exit 1
}

Write-Host "`n========================================" -ForegroundColor Green
Write-Host "  Backend image pushed successfully!" -ForegroundColor Green
Write-Host "  Backend : ${REGISTRY}/${NAMESPACE}/backend:${VERSION}" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Green
Write-Host "`nFrontend: run 'cd frontend && pnpm build' then upload dist/ to server" -ForegroundColor Cyan

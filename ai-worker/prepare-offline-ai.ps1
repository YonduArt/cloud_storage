$ErrorActionPreference = "Stop"

Set-Location $PSScriptRoot

$pythonCommand = $null
if (Get-Command python -ErrorAction SilentlyContinue) {
    $pythonCommand = @("python")
}

if ($null -eq $pythonCommand -and (Get-Command py -ErrorAction SilentlyContinue)) {
    $pythonCommand = @("py", "-3")
}

if ($null -eq $pythonCommand) {
    throw "Python not found. Install Python 3.11 or 3.12, then run this script again."
}

if (-not (Test-Path ".venv\Scripts\python.exe")) {
    if ($pythonCommand.Length -gt 1) {
        & $pythonCommand[0] $pythonCommand[1..($pythonCommand.Length - 1)] -m venv .venv
    } else {
        & $pythonCommand[0] -m venv .venv
    }
}

$requirementsHash = (Get-FileHash requirements.txt).Hash + (Get-FileHash requirements-models.txt).Hash
$markerPath = ".venv\.requirements.hash"
$installedHash = if (Test-Path $markerPath) { Get-Content $markerPath -Raw } else { "" }

if ($installedHash.Trim() -ne $requirementsHash) {
    .\.venv\Scripts\python.exe -m pip install --upgrade pip
    .\.venv\Scripts\python.exe -m pip install -r requirements.txt
    .\.venv\Scripts\python.exe -m pip install -r requirements-models.txt
    Set-Content -Path $markerPath -Value $requirementsHash
}

$env:MODEL_MODE = "auto"
$env:TEXT_MODEL = "intfloat/multilingual-e5-small"
$env:IMAGE_MODEL = "ViT-B-32"
$env:IMAGE_PRETRAINED = "laion400m_e32"
$env:OCR_ENABLED = "true"
$env:HF_HOME = Join-Path $PSScriptRoot ".models\huggingface"
$env:TORCH_HOME = Join-Path $PSScriptRoot ".models\torch"

$warmupScript = @'
import os
import tempfile

from PIL import Image

import app

print("Warming text embedding model...")
app.text_embedding("passage: test document about cloud storage", "passage")
app.text_embedding("cloud storage query", "query")

print("Warming CLIP image model...")
app.clip_text_embedding("a photo of a cat")

with tempfile.NamedTemporaryFile(delete=False, suffix=".png") as image_file:
    path = image_file.name

try:
    Image.new("RGB", (64, 64), color=(120, 180, 220)).save(path)
    app.image_embedding(path)
    print("Warming OCR model...")
    app.ocr_text(path)
finally:
    try:
        os.remove(path)
    except OSError:
        pass

print("Offline AI cache is prepared.")
'@

$warmupScript | .\.venv\Scripts\python.exe -

param([Parameter(Mandatory=$true)][string]$ImagePath, [Parameter(Mandatory=$true)][string]$OutputPath)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$taskErrorCode = 'OCR_FAILED'
$taskStream = $null
$taskBitmap = $null

try {
    Add-Type -AssemblyName System.Runtime.WindowsRuntime
    [void][Windows.Storage.StorageFile, Windows.Storage, ContentType = WindowsRuntime]
    [void][Windows.Storage.Streams.IRandomAccessStreamWithContentType, Windows.Storage.Streams, ContentType = WindowsRuntime]
    [void][Windows.Graphics.Imaging.BitmapDecoder, Windows.Graphics.Imaging, ContentType = WindowsRuntime]
    [void][Windows.Graphics.Imaging.BitmapTransform, Windows.Graphics.Imaging, ContentType = WindowsRuntime]
    [void][Windows.Graphics.Imaging.BitmapPixelFormat, Windows.Graphics.Imaging, ContentType = WindowsRuntime]
    [void][Windows.Graphics.Imaging.BitmapAlphaMode, Windows.Graphics.Imaging, ContentType = WindowsRuntime]
    [void][Windows.Graphics.Imaging.ExifOrientationMode, Windows.Graphics.Imaging, ContentType = WindowsRuntime]
    [void][Windows.Graphics.Imaging.ColorManagementMode, Windows.Graphics.Imaging, ContentType = WindowsRuntime]
    [void][Windows.Graphics.Imaging.SoftwareBitmap, Windows.Graphics.Imaging, ContentType = WindowsRuntime]
    [void][Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType = WindowsRuntime]
    [void][Windows.Media.Ocr.OcrResult, Windows.Foundation, ContentType = WindowsRuntime]

    $taskAsTask = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
        $_.Name -eq 'AsTask' -and $_.IsGenericMethodDefinition -and
        $_.GetGenericArguments().Length -eq 1 -and $_.GetParameters().Length -eq 1 -and
        $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
    } | Select-Object -First 1
    if (-not $taskAsTask) { throw 'OCR unavailable.' }

    function Wait-TaskOcrOperation($Operation, [Type]$ResultType) {
        $taskPending = $taskAsTask.MakeGenericMethod($ResultType).Invoke($null, @($Operation))
        if (-not $taskPending.Wait(10000)) { throw 'OCR timed out.' }
        return $taskPending.GetAwaiter().GetResult()
    }

    $taskEngine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages()
    if (-not $taskEngine) {
        $taskErrorCode = 'OCR_LANGUAGE_UNAVAILABLE'
        throw 'No installed OCR language.'
    }
    $taskFile = Wait-TaskOcrOperation ([Windows.Storage.StorageFile]::GetFileFromPathAsync($ImagePath)) ([Windows.Storage.StorageFile])
    $taskStream = Wait-TaskOcrOperation ($taskFile.OpenReadAsync()) ([Windows.Storage.Streams.IRandomAccessStreamWithContentType])
    $taskDecoder = Wait-TaskOcrOperation ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($taskStream)) ([Windows.Graphics.Imaging.BitmapDecoder])
    $taskTransform = New-Object Windows.Graphics.Imaging.BitmapTransform
    $taskMaximum = [Windows.Media.Ocr.OcrEngine]::MaxImageDimension
    $taskScale = [Math]::Min(1.0, $taskMaximum / [double][Math]::Max($taskDecoder.PixelWidth, $taskDecoder.PixelHeight))
    $taskTransform.ScaledWidth = [uint32][Math]::Max(1, [Math]::Floor($taskDecoder.PixelWidth * $taskScale))
    $taskTransform.ScaledHeight = [uint32][Math]::Max(1, [Math]::Floor($taskDecoder.PixelHeight * $taskScale))
    $taskBitmap = Wait-TaskOcrOperation ($taskDecoder.GetSoftwareBitmapAsync(
        [Windows.Graphics.Imaging.BitmapPixelFormat]::Bgra8,
        [Windows.Graphics.Imaging.BitmapAlphaMode]::Premultiplied,
        $taskTransform,
        [Windows.Graphics.Imaging.ExifOrientationMode]::IgnoreExifOrientation,
        [Windows.Graphics.Imaging.ColorManagementMode]::DoNotColorManage
    )) ([Windows.Graphics.Imaging.SoftwareBitmap])
    $taskRecognition = Wait-TaskOcrOperation ($taskEngine.RecognizeAsync($taskBitmap)) ([Windows.Media.Ocr.OcrResult])
    $taskText = ($taskRecognition.Lines | ForEach-Object { $_.Text }) -join "`n"
    $taskResult = @{ text = $taskText } | ConvertTo-Json -Compress
    [IO.File]::WriteAllText($OutputPath, $taskResult, (New-Object Text.UTF8Encoding $false))
} catch {
    # Report a fixed code only: never include the image, question text or PowerShell exception.
    try {
        [IO.File]::WriteAllText($OutputPath, (@{ error = $taskErrorCode } | ConvertTo-Json -Compress), (New-Object Text.UTF8Encoding $false))
    } catch { }
    exit 1
} finally {
    if ($null -ne $taskBitmap) { $taskBitmap.Dispose() }
    if ($null -ne $taskStream) { $taskStream.Dispose() }
}

param([switch]$Reordered, [switch]$MultiSku)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
$formats = @([System.Windows.Forms.Clipboard]::GetDataObject().GetFormats())
$wpsQa = New-Object -ComObject ket.Application
$qaBook = $wpsQa.Workbooks.Add()
try {
  $sheet = $qaBook.Worksheets.Item(1)
  $sheet.Name = 'Local_QA_Not_For_Business'
  $sheet.Range('A1').Select() | Out-Null
  $sheet.Paste()
  $result = [ordered]@{
    version = $wpsQa.Version
    metadataHeaders = @(1..5 | ForEach-Object { $sheet.Cells.Item(1,$_).Value2 })
    formats = $formats
    rows = $sheet.UsedRange.Rows.Count
    columns = $sheet.UsedRange.Columns.Count
    shapes = $sheet.Shapes.Count
    sku = $sheet.Range($(if ($Reordered) {'G3'} else {'F3'})).Value2
    firstPrice = $sheet.Range('J3').Value2
    averagePrice = $sheet.Range('J5').Value2
    skuMerged = $sheet.Range($(if ($Reordered) {'G3'} else {'F3'})).MergeArea.Address()
    photoMerged = $sheet.Range($(if ($Reordered) {'H3'} else {'G3'})).MergeArea.Address()
    provider = $sheet.Range($(if ($Reordered) {'M3'} else {'M5'})).Value2
    firstHeader = $sheet.Range('F1').Value2
    firstQuantity = $sheet.Range('J2').Value2
    secondQuantity = $sheet.Range('K2').Value2
    secondPrice = $sheet.Range('K3').Value2
    columnWidths = @($sheet.Columns.Item(6).Width,$sheet.Columns.Item(7).Width,$sheet.Columns.Item(13).Width)
    photoTopLeft = if ($sheet.Shapes.Count) { $sheet.Shapes.Item(1).TopLeftCell.Address() } else { '' }
    photoBottomRight = if ($sheet.Shapes.Count) { $sheet.Shapes.Item(1).BottomRightCell.Address() } else { '' }
    dataRows = @(for ($rowIndex=3; $rowIndex -le $sheet.UsedRange.Rows.Count; $rowIndex++) {
      if ($null -ne $sheet.Cells.Item($rowIndex,10).Value2) {
        [ordered]@{ prices=@($sheet.Cells.Item($rowIndex,10).Value2,$sheet.Cells.Item($rowIndex,11).Value2,$sheet.Cells.Item($rowIndex,12).Value2); provider=$sheet.Cells.Item($rowIndex,13).Value2; sku=$sheet.Cells.Item($rowIndex,6).MergeArea.Cells.Item(1,1).Value2; skuMerged=$sheet.Cells.Item($rowIndex,6).MergeArea.Address(); photoMerged=$sheet.Cells.Item($rowIndex,7).MergeArea.Address() }
      }
    })
  }
  $output = Join-Path (Get-Location) 'outputs/compact-quote'
  $target = Join-Path $output ('WPS-paste-' + (Get-Date -Format 'HHmmss') + '.xlsx')
  $qaBook.SaveAs($target,51)
  $sheet.Range('A1:M9').CopyPicture(1,2) | Out-Null
  $rendered = [System.Windows.Forms.Clipboard]::GetImage()
  if ($rendered) { $rendered.Save((Join-Path $output $(if ($Reordered) {'wps-reordered-render.png'} else {'wps-render.png'}))); $rendered.Dispose() }
  $result.file = $target
  $result | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $output $(if ($MultiSku) {'wps-multi-sku-result.json'} elseif ($Reordered) {'wps-reordered-result.json'} else {'wps-result.json'})) -Encoding UTF8
  $result | ConvertTo-Json -Depth 4
} finally { $wpsQa.CutCopyMode = 0; $qaBook.Close($false) }

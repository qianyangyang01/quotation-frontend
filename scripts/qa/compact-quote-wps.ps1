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
    formats = $formats
    rows = $sheet.UsedRange.Rows.Count
    columns = $sheet.UsedRange.Columns.Count
    shapes = $sheet.Shapes.Count
    sku = $sheet.Range($(if ($Reordered) {'B3'} else {'A3'})).Value2
    firstPrice = $sheet.Range('E3').Value2
    averagePrice = $sheet.Range('E5').Value2
    skuMerged = $sheet.Range($(if ($Reordered) {'B3'} else {'A3'})).MergeArea.Address()
    photoMerged = $sheet.Range($(if ($Reordered) {'C3'} else {'B3'})).MergeArea.Address()
    provider = $sheet.Range($(if ($Reordered) {'H3'} else {'H5'})).Value2
    firstHeader = $sheet.Range('A1').Value2
    firstQuantity = $sheet.Range('E2').Value2
    secondQuantity = $sheet.Range('F2').Value2
    secondPrice = $sheet.Range('F3').Value2
    columnWidths = @($sheet.Columns.Item(1).Width,$sheet.Columns.Item(2).Width,$sheet.Columns.Item(8).Width)
    photoTopLeft = if ($sheet.Shapes.Count) { $sheet.Shapes.Item(1).TopLeftCell.Address() } else { '' }
    photoBottomRight = if ($sheet.Shapes.Count) { $sheet.Shapes.Item(1).BottomRightCell.Address() } else { '' }
    dataRows = @(for ($rowIndex=3; $rowIndex -le $sheet.UsedRange.Rows.Count; $rowIndex++) {
      if ($null -ne $sheet.Cells.Item($rowIndex,5).Value2) {
        [ordered]@{ prices=@($sheet.Cells.Item($rowIndex,5).Value2,$sheet.Cells.Item($rowIndex,6).Value2,$sheet.Cells.Item($rowIndex,7).Value2); provider=$sheet.Cells.Item($rowIndex,8).Value2; sku=$sheet.Cells.Item($rowIndex,1).MergeArea.Cells.Item(1,1).Value2; skuMerged=$sheet.Cells.Item($rowIndex,1).MergeArea.Address(); photoMerged=$sheet.Cells.Item($rowIndex,2).MergeArea.Address() }
      }
    })
  }
  $output = Join-Path (Get-Location) 'outputs/compact-quote'
  $target = Join-Path $output ('WPS-paste-' + (Get-Date -Format 'HHmmss') + '.xlsx')
  $qaBook.SaveAs($target,51)
  $sheet.Range('A1:H9').CopyPicture(1,2) | Out-Null
  $rendered = [System.Windows.Forms.Clipboard]::GetImage()
  if ($rendered) { $rendered.Save((Join-Path $output $(if ($Reordered) {'wps-reordered-render.png'} else {'wps-render.png'}))); $rendered.Dispose() }
  $result.file = $target
  $result | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $output $(if ($MultiSku) {'wps-multi-sku-result.json'} elseif ($Reordered) {'wps-reordered-result.json'} else {'wps-result.json'})) -Encoding UTF8
  $result | ConvertTo-Json -Depth 4
} finally { $wpsQa.CutCopyMode = 0; $qaBook.Close($false) }

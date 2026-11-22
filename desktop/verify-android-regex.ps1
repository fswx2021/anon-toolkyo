param([string]$JarPath)

$ErrorActionPreference = 'Stop'
if (!$JarPath) { $JarPath = Join-Path $PSScriptRoot '..\shengjian-reader-workbench.jar' }
$JarPath = (Resolve-Path -LiteralPath $JarPath).Path

if (!('ShengjianIcuRegex' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;

public static class ShengjianIcuRegex
{
    [DllImport("icu.dll", CallingConvention = CallingConvention.Cdecl, CharSet = CharSet.Unicode)]
    public static extern IntPtr uregex_open(string pattern, int length, uint flags, IntPtr parseError, ref int status);

    [DllImport("icu.dll", CallingConvention = CallingConvention.Cdecl)]
    public static extern void uregex_close(IntPtr regex);

    [DllImport("icu.dll", CallingConvention = CallingConvention.Cdecl)]
    public static extern IntPtr u_errorName(int code);
}
'@
}

$patterns = @(& java -cp $JarPath com.shengjian.player.ReaderVerification --android-regex)
if ($LASTEXITCODE -ne 0 -or $patterns.Count -eq 0) { throw 'Could not read app patterns from the workbench JAR' }

$errors = @()
foreach ($line in $patterns) {
    $name, $javaFlags, $encoded = $line -split "`t", 3
    $expression = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($encoded))
    # These Java flags have the same values in ICU. Unicode case folding is already built in.
    $icuFlags = [uint32]([int]$javaFlags -band 0x3f)
    [int]$code = 0
    $regex = [ShengjianIcuRegex]::uregex_open($expression, $expression.Length,
        $icuFlags, [IntPtr]::Zero, [ref]$code)
    if ($regex -ne [IntPtr]::Zero) { [ShengjianIcuRegex]::uregex_close($regex) }
    if ($code -gt 0 -or $regex -eq [IntPtr]::Zero) {
        $errorName = [Runtime.InteropServices.Marshal]::PtrToStringAnsi([ShengjianIcuRegex]::u_errorName($code))
        $errors += "$name $errorName ($code)"
    }
}
if ($errors.Count -gt 0) { throw ("ICU rejected app patterns:`n" + ($errors -join "`n")) }
Write-Output "PASS ICU accepted all $($patterns.Count) static app patterns"

# @author kongweiguang
# SPDX-License-Identifier: GPL-3.0-or-later
<#
.SYNOPSIS
Starts an installed GUI on an owned, noninteractive Windows desktop for CDP acceptance.
.DESCRIPTION
The child inherits the caller's environment, but cannot show or focus windows on
the user's input desktop. No desktop switch or global input operation is used.
The parent runner owns timeout and process-tree cleanup; this wrapper preserves
the real application's exit status and closes its desktop handle on every path.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string] $ExecutablePath,
    [Parameter(Mandatory = $true)][string] $WorkingDirectory
)

$ErrorActionPreference = 'Stop'
if (-not $IsWindows) { throw 'Private desktop acceptance requires Windows.' }
$resolvedExecutable = (Resolve-Path -LiteralPath $ExecutablePath).ProviderPath
$resolvedDirectory = (Resolve-Path -LiteralPath $WorkingDirectory).ProviderPath
if (-not (Test-Path -LiteralPath $resolvedExecutable -PathType Leaf)) { throw 'Executable is not a file.' }
if (-not (Test-Path -LiteralPath $resolvedDirectory -PathType Container)) { throw 'Working directory is not a directory.' }

Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Text;

/// <summary>Owns only a newly created desktop and the process launched onto it; never switches the input desktop. @author kongweiguang</summary>
public static class JaPrivateDesktopLauncher {
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct StartupInfo {
        public int Size;
        public string Reserved;
        public string Desktop;
        public string Title;
        public int X, Y, XSize, YSize, XCountChars, YCountChars, FillAttribute, Flags;
        public short ShowWindow, ReservedSize;
        public IntPtr ReservedBytes, Input, Output, Error;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct ProcessInformation {
        public IntPtr Process, Thread;
        public uint ProcessId, ThreadId;
    }

    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr CreateDesktopW(string name, IntPtr device, IntPtr mode, uint flags, uint access, IntPtr security);
    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool CloseDesktop(IntPtr desktop);
    [DllImport("user32.dll", SetLastError = true)]
    private static extern IntPtr OpenInputDesktop(uint flags, bool inherit, uint access);
    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool GetUserObjectInformationW(IntPtr handle, int index, StringBuilder value, uint capacity, out uint required);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool CreateProcessW(string application, StringBuilder command, IntPtr processSecurity, IntPtr threadSecurity, bool inheritHandles, uint flags, IntPtr environment, string directory, ref StartupInfo startup, out ProcessInformation process);
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern uint WaitForSingleObject(IntPtr handle, uint milliseconds);
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool GetExitCodeProcess(IntPtr process, out uint code);
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool CloseHandle(IntPtr handle);

    /// <summary>Reads only the input desktop name to prove isolation without observing another application's content.</summary>
    private static string InputDesktopName() {
        IntPtr input = OpenInputDesktop(0, false, 0x0001);
        if (input == IntPtr.Zero) throw new Win32Exception(Marshal.GetLastWin32Error());
        try {
            StringBuilder name = new StringBuilder(512);
            uint required;
            if (!GetUserObjectInformationW(input, 2, name, 1024, out required))
                throw new Win32Exception(Marshal.GetLastWin32Error());
            return name.ToString();
        } finally { CloseDesktop(input); }
    }

    /// <summary>Uses an explicit private desktop at process creation so even application Show/Focus calls cannot reach the user's desktop.</summary>
    public static int Run(string executable, string directory) {
        string desktopName = "JaE2E_" + Guid.NewGuid().ToString("N");
        string inputName = InputDesktopName();
        if (String.Equals(desktopName, inputName, StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException("Private desktop unexpectedly equals input desktop.");
        IntPtr desktop = CreateDesktopW(desktopName, IntPtr.Zero, IntPtr.Zero, 0, 0x01FF, IntPtr.Zero);
        if (desktop == IntPtr.Zero) throw new Win32Exception(Marshal.GetLastWin32Error());
        ProcessInformation process = new ProcessInformation();
        try {
            StartupInfo startup = new StartupInfo();
            startup.Size = Marshal.SizeOf<StartupInfo>();
            startup.Desktop = "winsta0\\" + desktopName;
            startup.Flags = 0x00000001;
            startup.ShowWindow = 0;
            // A null environment inherits the isolated runner environment verbatim; stdout carries no config or credentials.
            if (!CreateProcessW(executable, new StringBuilder("\"" + executable + "\""), IntPtr.Zero, IntPtr.Zero, false, 0x08000400, IntPtr.Zero, directory, ref startup, out process))
                throw new Win32Exception(Marshal.GetLastWin32Error());
            Console.WriteLine("JA_PRIVATE_DESKTOP_STARTED {\"pid\":" + process.ProcessId + ",\"desktop\":\"" + desktopName + "\",\"privateDesktop\":true,\"inputDesktopDistinct\":true,\"switchDesktopUsed\":false}");
            Console.Out.Flush();
            uint wait = WaitForSingleObject(process.Process, UInt32.MaxValue);
            if (wait != 0) throw new Win32Exception(Marshal.GetLastWin32Error());
            uint exitCode;
            if (!GetExitCodeProcess(process.Process, out exitCode)) throw new Win32Exception(Marshal.GetLastWin32Error());
            return unchecked((int) exitCode);
        } finally {
            if (process.Thread != IntPtr.Zero) CloseHandle(process.Thread);
            if (process.Process != IntPtr.Zero) CloseHandle(process.Process);
            CloseDesktop(desktop);
        }
    }
}
'@

exit [JaPrivateDesktopLauncher]::Run($resolvedExecutable, $resolvedDirectory)

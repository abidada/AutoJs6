$ErrorActionPreference = 'SilentlyContinue'
Write-Output "=== java.exe command lines ==="
Get-CimInstance Win32_Process -Filter "Name='java.exe'" | ForEach-Object {
  $cl = $_.CommandLine
  if ($cl -eq $null) { $cl = '<null>' }
  if ($cl.Length -gt 260) { $cl = $cl.Substring(0,260) + '...' }
  Write-Output ("PID {0}: {1}" -f $_.ProcessId, ($cl -replace '\s+', ' '))
}
Write-Output ""
Write-Output "=== who locks the jar (Restart Manager) ==="
$jar = 'G:\code\autojs\source\AutoJs6\build-logic\ksp-version-codes-processor\build\libs\ksp-version-codes-processor.jar'

$sig = @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;

public static class FileLockFinder {
  [StructLayout(LayoutKind.Sequential)]
  struct RM_UNIQUE_PROCESS { public int dwProcessId; public System.Runtime.InteropServices.ComTypes.FILETIME ProcessStartTime; }

  const int RmRebootReasonNone = 0;
  const int CCH_RM_MAX_APP_NAME = 255;
  const int CCH_RM_MAX_SVC_NAME = 63;
  const int ERROR_MORE_DATA = 234;

  enum RM_APP_TYPE { RmUnknownApp = 0, RmMainWindow = 1, RmOtherWindow = 2, RmService = 3, RmExplorer = 4, RmConsole = 5, RmCritical = 1000 }

  [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
  struct RM_PROCESS_INFO {
    public RM_UNIQUE_PROCESS Process;
    [MarshalAs(UnmanagedType.ByValTStr, SizeConst = CCH_RM_MAX_APP_NAME + 1)] public string strAppName;
    [MarshalAs(UnmanagedType.ByValTStr, SizeConst = CCH_RM_MAX_SVC_NAME + 1)] public string strServiceShortName;
    public RM_APP_TYPE ApplicationType;
    public uint AppStatus;
    public uint TSSessionId;
    [MarshalAs(UnmanagedType.Bool)] public bool bRestartable;
  }

  [DllImport("rstrtmgr.dll", CharSet = CharSet.Unicode)]
  static extern int RmRegisterResources(uint pSessionHandle, uint nFiles, string[] rgsFilenames, uint nApplications, [In] RM_UNIQUE_PROCESS[] rgApplications, uint nServices, string[] rgsServiceNames);

  [DllImport("rstrtmgr.dll", CharSet = CharSet.Auto)]
  static extern int RmStartSession(out uint pSessionHandle, int dwSessionFlags, string strSessionKey);

  [DllImport("rstrtmgr.dll")]
  static extern int RmEndSession(uint pSessionHandle);

  [DllImport("rstrtmgr.dll")]
  static extern int RmGetList(uint dwSessionHandle, out uint pnProcInfoNeeded, ref uint pnProcInfo, [In, Out] RM_PROCESS_INFO[] rgAffectedApps, ref uint lpdwRebootReasons);

  public static List<string> FindLockers(string path) {
    uint handle;
    string key = Guid.NewGuid().ToString();
    var result = new List<string>();
    if (RmStartSession(out handle, 0, key) != 0) { result.Add("RmStartSession failed"); return result; }
    try {
      uint pnProcInfoNeeded = 0, pnProcInfo = 0, lpdwRebootReasons = RmRebootReasonNone;
      string[] files = new string[] { path };
      if (RmRegisterResources(handle, 1, files, 0, null, 0, null) != 0) { result.Add("RmRegisterResources failed"); return result; }
      int res = RmGetList(handle, out pnProcInfoNeeded, ref pnProcInfo, null, ref lpdwRebootReasons);
      if (res == ERROR_MORE_DATA) {
        var processInfo = new RM_PROCESS_INFO[pnProcInfoNeeded];
        pnProcInfo = pnProcInfoNeeded;
        res = RmGetList(handle, out pnProcInfoNeeded, ref pnProcInfo, processInfo, ref lpdwRebootReasons);
        if (res == 0) {
          for (int i = 0; i < pnProcInfo; i++) {
            try {
              var p = System.Diagnostics.Process.GetProcessById(processInfo[i].Process.dwProcessId);
              result.Add(string.Format("PID {0}: {1}", processInfo[i].Process.dwProcessId, p.ProcessName));
            } catch {
              result.Add(string.Format("PID {0}: <exited>", processInfo[i].Process.dwProcessId));
            }
          }
        } else { result.Add("RmGetList(2) failed: " + res); }
      } else if (res == 0) {
        result.Add("NO_LOCKER_FOUND");
      } else { result.Add("RmGetList failed: " + res); }
    } finally { RmEndSession(handle); }
    return result;
  }
}
'@
Add-Type -TypeDefinition $sig -Language CSharp
[FileLockFinder]::FindLockers($jar) | ForEach-Object { Write-Output $_ }

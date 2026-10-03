using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Win32;

namespace StorageSense.Launcher
{
    static class Program
    {
        private static NotifyIcon trayIcon;
        private static ContextMenu trayMenu;
        private static Process serverProcess = null;
        private static IntPtr jobHandle = IntPtr.Zero;
        private static string appUrl = "http://localhost:8501";
        private static int assignedPort = 8501;
        private static string baseDirectory = "";
        private static string pythonPath = "";
        private static string logFilePath = "";
        private static bool forceBrowser = false;

        #region Win32 Job Object (Ensures child python server is killed when launcher exits)
        [StructLayout(LayoutKind.Sequential)]
        struct IO_COUNTERS
        {
            public ulong ReadOperationCount;
            public ulong WriteOperationCount;
            public ulong OtherOperationCount;
            public ulong ReadTransferCount;
            public ulong WriteTransferCount;
            public ulong OtherTransferCount;
        }

        [StructLayout(LayoutKind.Sequential)]
        struct JOBOBJECT_BASIC_LIMIT_INFORMATION
        {
            public long PerProcessUserTimeLimit;
            public long PerJobUserTimeLimit;
            public uint LimitFlags;
            public UIntPtr MinimumWorkingSetSize;
            public UIntPtr MaximumWorkingSetSize;
            public uint ActiveProcessLimit;
            public UIntPtr Affinity;
            public uint PriorityClass;
            public uint SchedulingClass;
        }

        [StructLayout(LayoutKind.Sequential)]
        struct JOBOBJECT_EXTENDED_LIMIT_INFORMATION
        {
            public JOBOBJECT_BASIC_LIMIT_INFORMATION BasicLimitInformation;
            public IO_COUNTERS IoInfo;
            public UIntPtr ProcessMemoryLimit;
            public UIntPtr JobMemoryLimit;
            public UIntPtr PeakProcessMemoryLimit;
            public UIntPtr PeakJobMemoryLimit;
        }

        const int JobObjectExtendedLimitInformation = 9;
        const uint JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000;

        [DllImport("kernel32.dll", CharSet = CharSet.Auto, SetLastError = true)]
        static extern IntPtr CreateJobObject(IntPtr lpJobAttributes, string lpName);

        [DllImport("kernel32.dll", SetLastError = true)]
        static extern bool SetInformationJobObject(IntPtr hJob, int JobObjectInfoClass, IntPtr lpJobObjectInfo, uint cbJobObjectInfoLength);

        [DllImport("kernel32.dll", SetLastError = true)]
        static extern bool AssignProcessToJobObject(IntPtr hJob, IntPtr hProcess);

        [DllImport("kernel32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        static extern bool CloseHandle(IntPtr hObject);

        private static void InitializeJobObject()
        {
            try
            {
                jobHandle = CreateJobObject(IntPtr.Zero, null);
                var info = new JOBOBJECT_EXTENDED_LIMIT_INFORMATION();
                info.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
                int length = Marshal.SizeOf(typeof(JOBOBJECT_EXTENDED_LIMIT_INFORMATION));
                IntPtr extendedInfoPtr = Marshal.AllocHGlobal(length);
                Marshal.StructureToPtr(info, extendedInfoPtr, false);
                SetInformationJobObject(jobHandle, JobObjectExtendedLimitInformation, extendedInfoPtr, (uint)length);
                Marshal.FreeHGlobal(extendedInfoPtr);
            }
            catch
            {
                // Fallback gracefully if Job Objects are constrained
            }
        }
        #endregion

        [STAThread]
        static void Main(string[] args)
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);

            // Handle --stop
            for (int i = 0; i < args.Length; i++)
            {
                if (args[i].Equals("--stop", StringComparison.OrdinalIgnoreCase))
                {
                    StopAllInstances();
                    return;
                }
                else if (args[i].Equals("--browser", StringComparison.OrdinalIgnoreCase))
                {
                    forceBrowser = true;
                }
                else if (args[i].Equals("--port", StringComparison.OrdinalIgnoreCase) && i + 1 < args.Length)
                {
                    int.TryParse(args[i + 1], out assignedPort);
                }
            }

            // Resolve Base Directory dynamically
            baseDirectory = ResolveBaseDirectory();

            string logsDir = Path.Combine(baseDirectory, "logs");
            if (!Directory.Exists(logsDir))
            {
                Directory.CreateDirectory(logsDir);
            }
            logFilePath = Path.Combine(logsDir, "storagesense_app.log");

            // Check if server is already running on assigned port
            appUrl = string.Format("http://localhost:{0}", assignedPort);
            if (IsPortListening(assignedPort) && IsServerResponding(appUrl, 1500))
            {
                LaunchDesktopWindow(appUrl);
                return;
            }

            // If port is occupied by another application, find a free port
            if (IsPortListening(assignedPort))
            {
                assignedPort = FindFreePort(8502, 8520);
                appUrl = string.Format("http://localhost:{0}", assignedPort);
            }

            // Detect Python
            pythonPath = LocatePython();
            if (string.IsNullOrEmpty(pythonPath))
            {
                MessageBox.Show(
                    "Python runtime not found!\n\nPlease install Python 3.10+ (and add to PATH), or create a virtual environment in .venv.",
                    "StorageSense Desktop",
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Error
                );
                return;
            }

            InitializeJobObject();

            // Setup System Tray Icon
            SetupSystemTray();

            // Start background Python Streamlit server
            bool started = StartServer();
            if (!started)
            {
                MessageBox.Show(
                    "Failed to start StorageSense local engine.\nCheck log file:\n" + logFilePath,
                    "StorageSense Desktop Error",
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Error
                );
                Shutdown();
                return;
            }

            // Wait for server to become responsive in background thread
            ThreadPool.QueueUserWorkItem(delegate
            {
                bool ready = WaitForServer(appUrl, 35);
                if (ready)
                {
                    LaunchDesktopWindow(appUrl);
                    if (trayIcon != null)
                    {
                        trayIcon.ShowBalloonTip(
                            3000,
                            "StorageSense AI Ready",
                            "Desktop Assistant running locally on " + appUrl,
                            ToolTipIcon.Info
                        );
                    }
                }
                else
                {
                    MessageBox.Show(
                        "StorageSense server timed out during startup.\nCheck logs at:\n" + logFilePath,
                        "StorageSense Startup Warning",
                        MessageBoxButtons.OK,
                        MessageBoxIcon.Warning
                    );
                }
            });

            // Application event loop for System Tray
            Application.Run();
        }

        private static string ResolveBaseDirectory()
        {
            string current = AppDomain.CurrentDomain.BaseDirectory.TrimEnd('\\', '/');
            for (int i = 0; i < 4; i++)
            {
                if (string.IsNullOrEmpty(current)) break;
                if (File.Exists(Path.Combine(current, "ui", "app_streamlit.py")))
                    return current;
                if (File.Exists(Path.Combine(current, "pc_port", "ui", "app_streamlit.py")))
                    return Path.Combine(current, "pc_port");

                var parent = Directory.GetParent(current);
                if (parent == null) break;
                current = parent.FullName;
            }
            return AppDomain.CurrentDomain.BaseDirectory;
        }

        private static void SetupSystemTray()
        {
            trayMenu = new ContextMenu();
            trayMenu.MenuItems.Add("Open StorageSense Dashboard", OnOpenDashboard);
            trayMenu.MenuItems.Add("Open in Web Browser", OnOpenInBrowser);
            trayMenu.MenuItems.Add("-");
            trayMenu.MenuItems.Add("View Application Logs", OnViewLogs);
            trayMenu.MenuItems.Add("Restart AI Server", OnRestartServer);
            trayMenu.MenuItems.Add("-");
            trayMenu.MenuItems.Add("Exit StorageSense", OnExit);

            trayIcon = new NotifyIcon();
            trayIcon.Text = "StorageSense Local AI Assistant";
            trayIcon.ContextMenu = trayMenu;
            trayIcon.Visible = true;
            trayIcon.DoubleClick += OnOpenDashboard;

            // Load icon
            try
            {
                string iconPath = Path.Combine(baseDirectory, "resources", "app_icon.ico");
                if (File.Exists(iconPath))
                {
                    trayIcon.Icon = new Icon(iconPath);
                }
                else
                {
                    trayIcon.Icon = SystemIcons.Application;
                }
            }
            catch
            {
                trayIcon.Icon = SystemIcons.Application;
            }
        }

        private static bool StartServer()
        {
            try
            {
                string scriptPath = Path.Combine(baseDirectory, "ui", "app_streamlit.py");
                if (!File.Exists(scriptPath))
                {
                    string altRunApp = Path.Combine(baseDirectory, "run_app.py");
                    if (!File.Exists(altRunApp))
                    {
                        MessageBox.Show("Cannot locate ui/app_streamlit.py in: " + baseDirectory, "File Error", MessageBoxButtons.OK, MessageBoxIcon.Error);
                        return false;
                    }
                }

                string arguments = string.Format(
                    "-m streamlit run \"{0}\" --server.port={1} --server.headless=true --server.fileWatcherType=none --browser.gatherUsageStats=false",
                    scriptPath,
                    assignedPort
                );

                var startInfo = new ProcessStartInfo();
                startInfo.FileName = pythonPath;
                startInfo.Arguments = arguments;
                startInfo.WorkingDirectory = baseDirectory;
                startInfo.UseShellExecute = false;
                startInfo.CreateNoWindow = true;
                startInfo.WindowStyle = ProcessWindowStyle.Hidden;
                startInfo.RedirectStandardOutput = true;
                startInfo.RedirectStandardError = true;

                serverProcess = new Process();
                serverProcess.StartInfo = startInfo;
                serverProcess.EnableRaisingEvents = true;

                serverProcess.OutputDataReceived += (s, e) =>
                {
                    if (!string.IsNullOrEmpty(e.Data))
                    {
                        AppendLog("[SERVER] " + e.Data);
                    }
                };
                serverProcess.ErrorDataReceived += (s, e) =>
                {
                    if (!string.IsNullOrEmpty(e.Data))
                    {
                        AppendLog("[ERROR] " + e.Data);
                    }
                };

                AppendLog("--------------------------------------------------");
                AppendLog(string.Format("Starting StorageSense on {0} via {1}", appUrl, pythonPath));
                AppendLog("Working Directory: " + baseDirectory);
                AppendLog("--------------------------------------------------");

                bool success = serverProcess.Start();
                if (!success) return false;

                if (jobHandle != IntPtr.Zero)
                {
                    AssignProcessToJobObject(jobHandle, serverProcess.Handle);
                }

                serverProcess.BeginOutputReadLine();
                serverProcess.BeginErrorReadLine();
                return true;
            }
            catch (Exception ex)
            {
                AppendLog("Failed to start server process: " + ex.Message);
                return false;
            }
        }

        private static void AppendLog(string message)
        {
            try
            {
                string line = string.Format("[{0:yyyy-MM-dd HH:mm:ss}] {1}\r\n", DateTime.Now, message);
                File.AppendAllText(logFilePath, line);
            }
            catch { }
        }

        private static bool WaitForServer(string url, int timeoutSeconds)
        {
            int elapsed = 0;
            while (elapsed < timeoutSeconds * 1000)
            {
                if (IsServerResponding(url, 800))
                {
                    return true;
                }
                Thread.Sleep(500);
                elapsed += 500;
            }
            return false;
        }

        private static bool IsPortListening(int port)
        {
            try
            {
                using (var client = new TcpClient())
                {
                    var result = client.BeginConnect("127.0.0.1", port, null, null);
                    bool success = result.AsyncWaitHandle.WaitOne(400);
                    if (!success) return false;
                    client.EndConnect(result);
                    return true;
                }
            }
            catch
            {
                return false;
            }
        }

        private static bool IsServerResponding(string url, int timeoutMs)
        {
            try
            {
                var request = (HttpWebRequest)WebRequest.Create(url);
                request.Timeout = timeoutMs;
                request.Method = "GET";
                using (var response = (HttpWebResponse)request.GetResponse())
                {
                    return response.StatusCode == HttpStatusCode.OK;
                }
            }
            catch
            {
                return false;
            }
        }

        private static int FindFreePort(int start, int end)
        {
            for (int p = start; p <= end; p++)
            {
                if (!IsPortListening(p)) return p;
            }
            return start;
        }

        private static void LaunchDesktopWindow(string url)
        {
            if (forceBrowser)
            {
                Process.Start(url);
                return;
            }

            // Priority 1: Microsoft Edge App Mode (Chromium standalone window)
            string[] edgeCandidates = new string[]
            {
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86), "Microsoft", "Edge", "Application", "msedge.exe"),
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "Microsoft", "Edge", "Application", "msedge.exe"),
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Microsoft", "Edge", "Application", "msedge.exe")
            };

            foreach (var path in edgeCandidates)
            {
                if (File.Exists(path))
                {
                    var psi = new ProcessStartInfo();
                    psi.FileName = path;
                    psi.Arguments = string.Format("--app=\"{0}\" --window-size=1366,860", url);
                    psi.UseShellExecute = false;
                    Process.Start(psi);
                    return;
                }
            }

            // Priority 2: Google Chrome App Mode
            string[] chromeCandidates = new string[]
            {
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "Google", "Chrome", "Application", "chrome.exe"),
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86), "Google", "Chrome", "Application", "chrome.exe"),
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Google", "Chrome", "Application", "chrome.exe")
            };

            foreach (var path in chromeCandidates)
            {
                if (File.Exists(path))
                {
                    var psi = new ProcessStartInfo();
                    psi.FileName = path;
                    psi.Arguments = string.Format("--app=\"{0}\" --window-size=1366,860", url);
                    psi.UseShellExecute = false;
                    Process.Start(psi);
                    return;
                }
            }

            // Fallback: System default browser
            Process.Start(url);
        }

        private static string LocatePython()
        {
            // 1. Check local venv or bundled runtime
            string[] venvCandidates = new string[]
            {
                Path.Combine(baseDirectory, ".venv", "Scripts", "python.exe"),
                Path.Combine(baseDirectory, "runtime", "python.exe"),
                Path.Combine(baseDirectory, "python_embeded", "python.exe"),
                Path.Combine(baseDirectory, "..", ".venv", "Scripts", "python.exe")
            };
            foreach (var v in venvCandidates)
            {
                if (File.Exists(v)) return v;
            }

            // 2. Check VIRTUAL_ENV environment variable
            string venvEnv = Environment.GetEnvironmentVariable("VIRTUAL_ENV");
            if (!string.IsNullOrEmpty(venvEnv))
            {
                string venvPy = Path.Combine(venvEnv, "Scripts", "python.exe");
                if (File.Exists(venvPy)) return venvPy;
            }

            // 3. Check AppData Python Core (e.g. Python 3.14, 3.12, 3.11, etc.)
            string localAppData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            string pyDir = Path.Combine(localAppData, "Python");
            if (Directory.Exists(pyDir))
            {
                var coreDirs = Directory.GetDirectories(pyDir, "pythoncore*");
                foreach (var d in coreDirs)
                {
                    string cand = Path.Combine(d, "python.exe");
                    if (File.Exists(cand)) return cand;
                }
            }

            // 4. Check Programs/Python
            string programsPy = Path.Combine(localAppData, "Programs", "Python");
            if (Directory.Exists(programsPy))
            {
                var verDirs = Directory.GetDirectories(programsPy, "Python*");
                foreach (var d in verDirs)
                {
                    string cand = Path.Combine(d, "python.exe");
                    if (File.Exists(cand)) return cand;
                }
            }

            // 5. Check Registry (PythonCore)
            try
            {
                string regPy = GetPythonFromRegistry(Registry.CurrentUser);
                if (!string.IsNullOrEmpty(regPy)) return regPy;
                regPy = GetPythonFromRegistry(Registry.LocalMachine);
                if (!string.IsNullOrEmpty(regPy)) return regPy;
            }
            catch { }

            // 6. Check PATH
            string pathEnv = Environment.GetEnvironmentVariable("PATH");
            if (!string.IsNullOrEmpty(pathEnv))
            {
                string[] paths = pathEnv.Split(';');
                foreach (var p in paths)
                {
                    try
                    {
                        string trimmed = p.Trim();
                        if (string.IsNullOrEmpty(trimmed)) continue;
                        string cand = Path.Combine(trimmed, "python.exe");
                        if (File.Exists(cand)) return cand;
                    }
                    catch { }
                }
            }

            return "python";
        }

        private static string GetPythonFromRegistry(RegistryKey rootKey)
        {
            try
            {
                using (var key = rootKey.OpenSubKey(@"Software\Python\PythonCore"))
                {
                    if (key == null) return null;
                    foreach (var ver in key.GetSubKeyNames())
                    {
                        using (var pathKey = key.OpenSubKey(ver + @"\InstallPath"))
                        {
                            if (pathKey != null)
                            {
                                object val = pathKey.GetValue(null);
                                if (val != null)
                                {
                                    string exe = Path.Combine(val.ToString(), "python.exe");
                                    if (File.Exists(exe)) return exe;
                                }
                            }
                        }
                    }
                }
            }
            catch { }
            return null;
        }

        private static void StopAllInstances()
        {
            try
            {
                var procs = Process.GetProcessesByName("StorageSense");
                int currentId = Process.GetCurrentProcess().Id;
                foreach (var p in procs)
                {
                    if (p.Id != currentId)
                    {
                        p.Kill();
                    }
                }
            }
            catch { }
        }

        #region Event Handlers
        private static void OnOpenDashboard(object sender, EventArgs e)
        {
            LaunchDesktopWindow(appUrl);
        }

        private static void OnOpenInBrowser(object sender, EventArgs e)
        {
            Process.Start(appUrl);
        }

        private static void OnViewLogs(object sender, EventArgs e)
        {
            if (File.Exists(logFilePath))
            {
                Process.Start("notepad.exe", logFilePath);
            }
            else
            {
                MessageBox.Show("No log file found yet at: " + logFilePath, "Logs", MessageBoxButtons.OK, MessageBoxIcon.Information);
            }
        }

        private static void OnRestartServer(object sender, EventArgs e)
        {
            StopServer();
            Thread.Sleep(1000);
            StartServer();
            ThreadPool.QueueUserWorkItem(delegate
            {
                if (WaitForServer(appUrl, 25))
                {
                    if (trayIcon != null)
                    {
                        trayIcon.ShowBalloonTip(2000, "Server Restarted", "StorageSense server restarted successfully.", ToolTipIcon.Info);
                    }
                }
            });
        }

        private static void StopServer()
        {
            try
            {
                if (serverProcess != null && !serverProcess.HasExited)
                {
                    serverProcess.Kill();
                    serverProcess.WaitForExit(3000);
                    serverProcess = null;
                }
            }
            catch { }
        }

        private static void OnExit(object sender, EventArgs e)
        {
            Shutdown();
        }

        private static void Shutdown()
        {
            StopServer();
            if (jobHandle != IntPtr.Zero)
            {
                CloseHandle(jobHandle);
                jobHandle = IntPtr.Zero;
            }
            if (trayIcon != null)
            {
                trayIcon.Visible = false;
                trayIcon.Dispose();
            }
            Application.Exit();
        }
        #endregion
    }
}

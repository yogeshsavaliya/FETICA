using UnityEngine;

namespace ProxyTunnel
{
    public sealed class ProxyTunnelBootstrap : MonoBehaviour
    {
        public static ProxyTunnelBootstrap Instance { get; private set; }

        [SerializeField] private string gatewayHost = "127.0.0.1";
        [SerializeField] private int gatewayPort = 9090;
        [SerializeField] private string username = string.Empty;
        [SerializeField] private string password = string.Empty;
        [SerializeField] private bool useTls = true;
        [SerializeField] private bool showDebugUi = true;

        private const int DebugPanelWidth = 460;
        private string debugHost;
        private string debugPort;
        private string debugUsername;
        private string debugPassword;
        private bool debugUseTls;
        private Vector2 debugScroll;

        public string GatewayHost
        {
            get => gatewayHost;
            set => gatewayHost = value;
        }

        public int GatewayPort
        {
            get => gatewayPort;
            set => gatewayPort = value;
        }

        public string Username
        {
            get => username;
            set => username = value;
        }

        public string Password
        {
            get => password;
            set => password = value;
        }

        public bool UseTls
        {
            get => useTls;
            set => useTls = value;
        }

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
        private static void EnsureBootstrapExists()
        {
            if (Instance != null)
            {
                return;
            }

            ProxyTunnelBootstrap sceneBootstrap = FindFirstObjectByType<ProxyTunnelBootstrap>();
            if (sceneBootstrap != null)
            {
                Instance = sceneBootstrap;
                DontDestroyOnLoad(sceneBootstrap.gameObject);
                return;
            }

            GameObject host = new GameObject("ProxyTunnelBootstrap");
            host.AddComponent<ProxyTunnelBootstrap>();
            DontDestroyOnLoad(host);
        }

        private void Awake()
        {
            if (Instance != null && Instance != this)
            {
                Destroy(gameObject);
                return;
            }

            Instance = this;
            DontDestroyOnLoad(gameObject);
            SyncDebugFieldsFromConfig();
        }

        public bool StartTunnel()
        {
            return ProxyTunnelClient.StartTunnel(gatewayHost, gatewayPort, username, password, useTls);
        }

        public bool StartTunnel(string host, int port, string authUsername, string authPassword, bool tls)
        {
            gatewayHost = host;
            gatewayPort = port;
            username = authUsername;
            password = authPassword;
            useTls = tls;
            SyncDebugFieldsFromConfig();
            return StartTunnel();
        }

        public void StopTunnel()
        {
            ProxyTunnelClient.StopTunnel();
        }

        private void OnGUI()
        {
            if (!showDebugUi)
            {
                return;
            }

            GUILayout.BeginArea(new Rect(20, 20, DebugPanelWidth, Screen.height - 40), GUI.skin.box);
            debugScroll = GUILayout.BeginScrollView(debugScroll);

            GUILayout.Label("Proxy Tunnel Phase 2 - Reverse SOCKS");
            GUILayout.Label("Gateway host/port is the tunnel listener. SOCKS runs on the gateway machine.");

            GUILayout.Label("Gateway host");
            debugHost = GUILayout.TextField(debugHost ?? string.Empty);

            GUILayout.Label("Gateway tunnel port");
            debugPort = GUILayout.TextField(debugPort ?? string.Empty);

            GUILayout.Label("Username");
            debugUsername = GUILayout.TextField(debugUsername ?? string.Empty);

            GUILayout.Label("Password");
            debugPassword = GUILayout.PasswordField(debugPassword ?? string.Empty, '*');

            debugUseTls = GUILayout.Toggle(debugUseTls, "Use TLS");

            GUILayout.Space(8);
            GUILayout.BeginHorizontal();
            if (GUILayout.Button("Start Reverse SOCKS Tunnel", GUILayout.Height(40)))
            {
                StartTunnelFromDebugUi();
            }
            if (GUILayout.Button("Stop", GUILayout.Height(40)))
            {
                StopTunnel();
            }
            GUILayout.EndHorizontal();

            GUILayout.Space(12);
            GUILayout.Label("Current status: " + ProxyTunnelClient.GetStatus());
            GUILayout.Label("Tunnel uploaded bytes: " + ProxyTunnelClient.GetUploadedBytes());
            GUILayout.Label("Tunnel downloaded bytes: " + ProxyTunnelClient.GetDownloadedBytes());
            GUILayout.Label("Last error: " + ProxyTunnelClient.GetLastError());

            GUILayout.EndScrollView();
            GUILayout.EndArea();
        }

        private void StartTunnelFromDebugUi()
        {
            if (!int.TryParse(debugPort, out int parsedPort))
            {
                return;
            }

            StartTunnel(debugHost, parsedPort, debugUsername, debugPassword, debugUseTls);
        }

        private void SyncDebugFieldsFromConfig()
        {
            debugHost = gatewayHost;
            debugPort = gatewayPort.ToString();
            debugUsername = username;
            debugPassword = password;
            debugUseTls = useTls;
        }

        private void OnApplicationPause(bool pauseStatus)
        {
            // The user-visible foreground service owns tunnel lifetime while the app is paused.
        }

        private void OnApplicationFocus(bool hasFocus)
        {
            // Losing Unity focus is not user consent to stop the foreground service.
        }

        private void OnApplicationQuit()
        {
            // Android may not call this reliably; notification Stop is the explicit cleanup path.
        }
    }
}

using UnityEngine;

namespace ProxyTunnel
{
    public sealed class ProxyTunnelBootstrap : MonoBehaviour
    {
        public static ProxyTunnelBootstrap Instance { get; private set; }

        [SerializeField] private string gatewayHost = "127.0.0.1";
        [SerializeField] private int gatewayPort = 9090;
        [SerializeField] private string token = string.Empty;
        [SerializeField] private bool useTls = true;

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

        public string Token
        {
            get => token;
            set => token = value;
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
                EnsureDebugUi(Instance.gameObject);
                return;
            }

            ProxyTunnelBootstrap sceneBootstrap = FindFirstObjectByType<ProxyTunnelBootstrap>();
            if (sceneBootstrap != null)
            {
                Instance = sceneBootstrap;
                DontDestroyOnLoad(sceneBootstrap.gameObject);
                EnsureDebugUi(sceneBootstrap.gameObject);
                return;
            }

            GameObject host = new GameObject("ProxyTunnelBootstrap");
            host.AddComponent<ProxyTunnelBootstrap>();
            EnsureDebugUi(host);
            DontDestroyOnLoad(host);
        }

        private static void EnsureDebugUi(GameObject host)
        {
            if (host.GetComponent<ProxyTunnelDebugUI>() == null)
            {
                host.AddComponent<ProxyTunnelDebugUI>();
            }
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
        }

        public bool StartTunnel()
        {
            return ProxyTunnelClient.StartTunnel(gatewayHost, gatewayPort, token, useTls);
        }

        public bool StartTunnel(string host, int port, string authToken, bool tls)
        {
            gatewayHost = host;
            gatewayPort = port;
            token = authToken;
            useTls = tls;
            return StartTunnel();
        }

        public void StopTunnel()
        {
            ProxyTunnelClient.StopTunnel();
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

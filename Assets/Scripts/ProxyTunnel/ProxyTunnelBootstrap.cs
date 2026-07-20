using System;
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

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.BeforeSceneLoad)]
        private static void EnsureBootstrapExists()
        {
            if (Instance != null)
            {
                return;
            }

            GameObject host = new GameObject("ProxyTunnelBootstrap");
            host.AddComponent<ProxyTunnelBootstrap>();
            AttachDebugUiIfAvailable(host);
            DontDestroyOnLoad(host);
        }

        private static void AttachDebugUiIfAvailable(GameObject host)
        {
            Type debugUiType = Type.GetType("ProxyTunnel.ProxyTunnelDebugUI, Assembly-CSharp")
                ?? Type.GetType("ProxyTunnel.ProxyTunnelDebugUI");
            if (debugUiType != null && typeof(MonoBehaviour).IsAssignableFrom(debugUiType))
            {
                host.AddComponent(debugUiType);
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

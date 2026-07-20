using UnityEngine;

namespace ProxyTunnel
{
    public sealed class ProxyTunnelDebugUI : MonoBehaviour
    {
        private const int PanelWidth = 420;

        private string host = "127.0.0.1";
        private string port = "9090";
        private string token = string.Empty;
        private bool useTls = true;
        private Vector2 scroll;

        private void Start()
        {
            if (ProxyTunnelBootstrap.Instance == null)
            {
                return;
            }

            host = ProxyTunnelBootstrap.Instance.GatewayHost;
            port = ProxyTunnelBootstrap.Instance.GatewayPort.ToString();
            token = ProxyTunnelBootstrap.Instance.Token;
            useTls = ProxyTunnelBootstrap.Instance.UseTls;
        }

        private void OnGUI()
        {
            GUILayout.BeginArea(new Rect(20, 20, PanelWidth, Screen.height - 40), GUI.skin.box);
            scroll = GUILayout.BeginScrollView(scroll);

            GUILayout.Label("Proxy Tunnel Phase 1");

            GUILayout.Label("Gateway host");
            host = GUILayout.TextField(host);

            GUILayout.Label("Gateway port");
            port = GUILayout.TextField(port);

            GUILayout.Label("Token");
            token = GUILayout.PasswordField(token, '*');

            useTls = GUILayout.Toggle(useTls, "Use TLS");

            GUILayout.Space(8);
            GUILayout.BeginHorizontal();
            if (GUILayout.Button("Start", GUILayout.Height(40)))
            {
                StartTunnelFromUi();
            }
            if (GUILayout.Button("Stop", GUILayout.Height(40)))
            {
                ProxyTunnelBootstrap.Instance?.StopTunnel();
            }
            GUILayout.EndHorizontal();

            GUILayout.Space(12);
            GUILayout.Label("Current status: " + ProxyTunnelClient.GetStatus());
            GUILayout.Label("Uploaded bytes: " + ProxyTunnelClient.GetUploadedBytes());
            GUILayout.Label("Downloaded bytes: " + ProxyTunnelClient.GetDownloadedBytes());
            GUILayout.Label("Last error: " + ProxyTunnelClient.GetLastError());

            GUILayout.EndScrollView();
            GUILayout.EndArea();
        }

        private void StartTunnelFromUi()
        {
            if (!int.TryParse(port, out int parsedPort))
            {
                return;
            }

            ProxyTunnelBootstrap bootstrap = ProxyTunnelBootstrap.Instance;
            if (bootstrap == null)
            {
                return;
            }

            bootstrap.StartTunnel(host, parsedPort, token, useTls);
        }
    }
}

using System;

#if UNITY_ANDROID && !UNITY_EDITOR
using UnityEngine;
#endif

namespace ProxyTunnel
{
    public static class ProxyTunnelClient
    {
        private const string BridgeClassName = "com.secureinfotech.proxytunnel.ProxyTunnelBridge";

        public static bool StartTunnel(string host, int port, string username, string password, bool useTls)
        {
            string validationError = ValidateStartInput(host, port, username, password);
            if (validationError != null)
            {
                LastEditorError = validationError;
                return false;
            }

#if UNITY_ANDROID && !UNITY_EDITOR
            using (AndroidJavaClass unityPlayer = new AndroidJavaClass("com.unity3d.player.UnityPlayer"))
            using (AndroidJavaObject activity = unityPlayer.GetStatic<AndroidJavaObject>("currentActivity"))
            using (AndroidJavaClass bridge = new AndroidJavaClass(BridgeClassName))
            {
                bridge.CallStatic("startTunnel", activity, host.Trim(), port, username, password, useTls);
            }
            return true;
#else
            LastEditorError = "Proxy tunnel is only available on Android devices.";
            return false;
#endif
        }

        public static void StopTunnel()
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            using (AndroidJavaClass unityPlayer = new AndroidJavaClass("com.unity3d.player.UnityPlayer"))
            using (AndroidJavaObject activity = unityPlayer.GetStatic<AndroidJavaObject>("currentActivity"))
            using (AndroidJavaClass bridge = new AndroidJavaClass(BridgeClassName))
            {
                bridge.CallStatic("stopTunnel", activity);
            }
#else
            LastEditorStatus = ProxyTunnelStatus.Disconnected;
#endif
        }

        public static ProxyTunnelStatus GetStatus()
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            using (AndroidJavaClass bridge = new AndroidJavaClass(BridgeClassName))
            {
                string status = bridge.CallStatic<string>("getStatus");
                return ParseStatus(status);
            }
#else
            return LastEditorStatus;
#endif
        }

        public static long GetUploadedBytes()
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            using (AndroidJavaClass bridge = new AndroidJavaClass(BridgeClassName))
            {
                return bridge.CallStatic<long>("getUploadedBytes");
            }
#else
            return 0L;
#endif
        }

        public static long GetDownloadedBytes()
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            using (AndroidJavaClass bridge = new AndroidJavaClass(BridgeClassName))
            {
                return bridge.CallStatic<long>("getDownloadedBytes");
            }
#else
            return 0L;
#endif
        }

        public static string GetLastError()
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            using (AndroidJavaClass bridge = new AndroidJavaClass(BridgeClassName))
            {
                return bridge.CallStatic<string>("getLastError") ?? string.Empty;
            }
#else
            return LastEditorError;
#endif
        }

        private static string LastEditorError { get; set; } = string.Empty;
        private static ProxyTunnelStatus LastEditorStatus { get; set; } = ProxyTunnelStatus.Disconnected;

        private static string ValidateStartInput(string host, int port, string username, string password)
        {
            if (string.IsNullOrWhiteSpace(host))
            {
                return "Gateway host is required.";
            }
            if (port < 1 || port > 65535)
            {
                return "Gateway port must be between 1 and 65535.";
            }
            if (string.IsNullOrEmpty(username))
            {
                return "Username is required.";
            }
            if (string.IsNullOrEmpty(password))
            {
                return "Password is required.";
            }
            if (username.IndexOf('\n') >= 0 || username.IndexOf('\r') >= 0
                || password.IndexOf('\n') >= 0 || password.IndexOf('\r') >= 0)
            {
                return "Username and password must not contain newline characters.";
            }
            return null;
        }

        private static ProxyTunnelStatus ParseStatus(string status)
        {
            if (string.Equals(status, "Connecting", StringComparison.OrdinalIgnoreCase))
            {
                return ProxyTunnelStatus.Connecting;
            }
            if (string.Equals(status, "Connected", StringComparison.OrdinalIgnoreCase))
            {
                return ProxyTunnelStatus.Connected;
            }
            if (string.Equals(status, "Reconnecting", StringComparison.OrdinalIgnoreCase))
            {
                return ProxyTunnelStatus.Reconnecting;
            }
            return ProxyTunnelStatus.Disconnected;
        }
    }
}

using System;

namespace PushBox.Windows.Crypto;

/// <summary>
/// The protocol's one base64 flavour: URL-safe alphabet, no padding (§4.3, §5.2).
/// .NET 8 has no System.Base64Url, so the alphabet translation lives here.
/// </summary>
public static class B64Url
{
    public static string Encode(byte[] data)
    {
        return Convert.ToBase64String(data).TrimEnd('=').Replace('+', '-').Replace('/', '_');
    }

    public static byte[] Decode(string text)
    {
        var base64 = text.Replace('-', '+').Replace('_', '/');
        switch (base64.Length % 4)
        {
            case 2: base64 += "=="; break;
            case 3: base64 += "="; break;
        }
        return Convert.FromBase64String(base64);
    }
}

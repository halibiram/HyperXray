#!/usr/bin/env python3
"""
MCP Server'larını test eden script
"""

import subprocess
import sys
import json
import time

def test_mcp_server(name, command, args):
    """MCP server'ını test eder"""
    print(f"\n🧪 Testing {name}...")

    try:
        # MCP initialize mesajı gönder
        init_msg = {
            "jsonrpc": "2.0",
            "id": 1,
            "method": "initialize",
            "params": {
                "protocolVersion": "2024-11-05",
                "capabilities": {},
                "clientInfo": {
                    "name": "test-client",
                    "version": "1.0.0"
                }
            }
        }

        # Process başlat
        proc = subprocess.Popen(
            [command] + args,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True
        )

        # Initialize mesajı gönder
        proc.stdin.write(json.dumps(init_msg) + '\n')
        proc.stdin.flush()

        # Response oku
        response = proc.stdout.readline()
        if response:
            try:
                result = json.loads(response)
                if 'result' in result:
                    print(f"✅ {name}: MCP bağlantısı başarılı")

                    # Tools listesini al
                    tools_msg = {
                        "jsonrpc": "2.0",
                        "id": 2,
                        "method": "tools/list"
                    }
                    proc.stdin.write(json.dumps(tools_msg) + '\n')
                    proc.stdin.flush()

                    tools_response = proc.stdout.readline()
                    if tools_response:
                        tools_result = json.loads(tools_response)
                        if 'result' in tools_result and 'tools' in tools_result['result']:
                            tools = tools_result['result']['tools']
                            print(f"   📋 {len(tools)} tool bulundu")
                            for tool in tools[:3]:  # İlk 3 tool'u göster
                                print(f"      - {tool['name']}: {tool['description'][:50]}...")

                    return True
                else:
                    print(f"❌ {name}: Initialize başarısız")
                    return False
            except json.JSONDecodeError:
                print(f"❌ {name}: Geçersiz JSON response")
                return False
        else:
            print(f"❌ {name}: Response alınamadı")
            return False

    except Exception as e:
        print(f"❌ {name}: Hata - {e}")
        return False
    finally:
        if 'proc' in locals():
            proc.terminate()

def main():
    print("🚀 MCP Server Test Suite")
    print("=" * 50)

    # Test edilecek server'lar
    servers_to_test = [
        ("Brave Search MCP", "npx", ["-y", "@brave/brave-search-mcp-server"]),
        ("Memory Bank MCP", "npx", ["-y", "memory-bank-mcp", "serve"]),
        ("Better Playwright MCP", "npx", ["-y", "better-playwright-mcp3@latest", "mcp"]),
        ("Custom GitHub MCP", "python", ["custom_github_mcp_server.py"]),
        ("Custom AWS MCP", "python", ["custom_aws_mcp_server.py"])
    ]

    results = {}

    for name, cmd, args in servers_to_test:
        success = test_mcp_server(name, cmd, args)
        results[name] = success
        time.sleep(1)  # Kısa bekleme

    # Sonuçları özetle
    print("\n" + "=" * 50)
    print("📊 TEST SONUÇLARI:")
    print("=" * 50)

    successful = 0
    total = len(results)

    for name, success in results.items():
        status = "✅" if success else "❌"
        print(f"{status} {name}")
        if success:
            successful += 1

    print(f"\n🎯 Toplam: {successful}/{total} server çalışıyor")

    if successful >= 3:
        print("🎉 Harika! Çoğu MCP server çalışıyor.")
        print("Cursor'a MCP server'larını ekleyebilirsiniz.")
    else:
        print("⚠️  Bazı server'lar çalışmıyor. Sorun giderme gerekebilir.")

if __name__ == "__main__":
    main()



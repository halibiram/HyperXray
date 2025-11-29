#!/usr/bin/env python3
"""
Custom GitHub MCP Server - GitHub CLI kullanarak GitHub işlemlerini MCP üzerinden sağlar
"""

import sys
import json
import subprocess
import os
from typing import Dict, Any, List
import logging

# MCP protokolü için gerekli
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

class GitHubMCPServer:
    def __init__(self):
        self.tools = [
            {
                "name": "github_list_issues",
                "description": "List issues in a GitHub repository",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "repo": {
                            "type": "string",
                            "description": "Repository in format 'owner/repo'"
                        },
                        "state": {
                            "type": "string",
                            "enum": ["open", "closed", "all"],
                            "description": "Issue state (default: open)"
                        },
                        "limit": {
                            "type": "number",
                            "description": "Maximum number of issues to return (default: 10)"
                        }
                    },
                    "required": ["repo"]
                }
            },
            {
                "name": "github_list_pull_requests",
                "description": "List pull requests in a GitHub repository",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "repo": {
                            "type": "string",
                            "description": "Repository in format 'owner/repo'"
                        },
                        "state": {
                            "type": "string",
                            "enum": ["open", "closed", "all"],
                            "description": "PR state (default: open)"
                        },
                        "limit": {
                            "type": "number",
                            "description": "Maximum number of PRs to return (default: 10)"
                        }
                    },
                    "required": ["repo"]
                }
            },
            {
                "name": "github_get_issue",
                "description": "Get details of a specific GitHub issue",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "repo": {
                            "type": "string",
                            "description": "Repository in format 'owner/repo'"
                        },
                        "number": {
                            "type": "number",
                            "description": "Issue number"
                        }
                    },
                    "required": ["repo", "number"]
                }
            },
            {
                "name": "github_create_issue",
                "description": "Create a new GitHub issue",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "repo": {
                            "type": "string",
                            "description": "Repository in format 'owner/repo'"
                        },
                        "title": {
                            "type": "string",
                            "description": "Issue title"
                        },
                        "body": {
                            "type": "string",
                            "description": "Issue body/description"
                        },
                        "labels": {
                            "type": "array",
                            "items": {"type": "string"},
                            "description": "List of labels to apply"
                        }
                    },
                    "required": ["repo", "title"]
                }
            },
            {
                "name": "github_search_repositories",
                "description": "Search for GitHub repositories",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "query": {
                            "type": "string",
                            "description": "Search query"
                        },
                        "limit": {
                            "type": "number",
                            "description": "Maximum number of results (default: 10)"
                        }
                    },
                    "required": ["query"]
                }
            }
        ]

    def run_gh_cli(self, command: List[str]) -> Dict[str, Any]:
        """GitHub CLI komutu çalıştırır"""
        try:
            result = subprocess.run(
                ['gh'] + command,
                capture_output=True,
                text=True,
                timeout=30
            )

            if result.returncode == 0:
                try:
                    return json.loads(result.stdout)
                except json.JSONDecodeError:
                    return {"output": result.stdout.strip()}
            else:
                return {
                    "error": result.stderr.strip(),
                    "returncode": result.returncode
                }
        except subprocess.TimeoutExpired:
            return {"error": "Command timed out"}
        except FileNotFoundError:
            return {"error": "GitHub CLI not found. Please install GitHub CLI."}

    def handle_list_issues(self, params: Dict[str, Any]) -> Dict[str, Any]:
        """Repository'deki issue'ları listeler"""
        repo = params.get('repo')
        state = params.get('state', 'open')
        limit = params.get('limit', 10)

        if not repo:
            return {"error": "repo parameter required"}

        result = self.run_gh_cli([
            'issue', 'list',
            '--repo', repo,
            '--state', state,
            '--limit', str(limit),
            '--json', 'number,title,labels,createdAt,updatedAt'
        ])

        if 'error' in result:
            return result

        return result

    def handle_list_pull_requests(self, params: Dict[str, Any]) -> Dict[str, Any]:
        """Repository'deki PR'ları listeler"""
        repo = params.get('repo')
        state = params.get('state', 'open')
        limit = params.get('limit', 10)

        if not repo:
            return {"error": "repo parameter required"}

        result = self.run_gh_cli([
            'pr', 'list',
            '--repo', repo,
            '--state', state,
            '--limit', str(limit),
            '--json', 'number,title,headRefName,createdAt,updatedAt'
        ])

        if 'error' in result:
            return result

        return result

    def handle_get_issue(self, params: Dict[str, Any]) -> Dict[str, Any]:
        """Belirli bir issue'nun detaylarını getirir"""
        repo = params.get('repo')
        number = params.get('number')

        if not repo or not number:
            return {"error": "repo and number parameters required"}

        result = self.run_gh_cli([
            'issue', 'view', str(number),
            '--repo', repo,
            '--json', 'number,title,body,labels,comments,createdAt,updatedAt'
        ])

        if 'error' in result:
            return result

        return result

    def handle_create_issue(self, params: Dict[str, Any]) -> Dict[str, Any]:
        """Yeni issue oluşturur"""
        repo = params.get('repo')
        title = params.get('title')
        body = params.get('body', '')
        labels = params.get('labels', [])

        if not repo or not title:
            return {"error": "repo and title parameters required"}

        command = [
            'issue', 'create',
            '--repo', repo,
            '--title', title
        ]

        if body:
            command.extend(['--body', body])

        if labels:
            command.extend(['--label', ','.join(labels)])

        result = self.run_gh_cli(command)

        if 'error' in result:
            return result

        return {"message": "Issue created successfully", "url": result.get('output', '')}

    def handle_search_repositories(self, params: Dict[str, Any]) -> Dict[str, Any]:
        """GitHub repository'leri arar"""
        query = params.get('query')
        limit = params.get('limit', 10)

        if not query:
            return {"error": "query parameter required"}

        result = self.run_gh_cli([
            'search', 'repos', query,
            '--limit', str(limit),
            '--json', 'fullName,description,stargazersCount,language,updatedAt'
        ])

        if 'error' in result:
            return result

        return result

    def process_request(self, request: Dict[str, Any]) -> Dict[str, Any]:
        """MCP request'ini işler"""
        try:
            if request.get('method') == 'tools/list':
                return {
                    "jsonrpc": "2.0",
                    "id": request.get('id'),
                    "result": {
                        "tools": self.tools
                    }
                }

            elif request.get('method') == 'tools/call':
                tool_name = request.get('params', {}).get('name')
                tool_params = request.get('params', {}).get('arguments', {})

                result = None

                if tool_name == 'github_list_issues':
                    result = self.handle_list_issues(tool_params)
                elif tool_name == 'github_list_pull_requests':
                    result = self.handle_list_pull_requests(tool_params)
                elif tool_name == 'github_get_issue':
                    result = self.handle_get_issue(tool_params)
                elif tool_name == 'github_create_issue':
                    result = self.handle_create_issue(tool_params)
                elif tool_name == 'github_search_repositories':
                    result = self.handle_search_repositories(tool_params)
                else:
                    result = {"error": f"Unknown tool: {tool_name}"}

                return {
                    "jsonrpc": "2.0",
                    "id": request.get('id'),
                    "result": result
                }

            else:
                return {
                    "jsonrpc": "2.0",
                    "id": request.get('id'),
                    "error": {
                        "code": -32601,
                        "message": "Method not found"
                    }
                }

        except Exception as e:
            logger.error(f"Error processing request: {e}")
            return {
                "jsonrpc": "2.0",
                "id": request.get('id'),
                "error": {
                    "code": -32000,
                    "message": str(e)
                }
            }

def main():
    server = GitHubMCPServer()

    # MCP initialize
    init_request = json.loads(input())
    if init_request.get('method') == 'initialize':
        response = {
            "jsonrpc": "2.0",
            "id": init_request.get('id'),
            "result": {
                "protocolVersion": "2024-11-05",
                "capabilities": {
                    "tools": {
                        "listChanged": True
                    }
                },
                "serverInfo": {
                    "name": "custom-github-mcp-server",
                    "version": "1.0.0"
                }
            }
        }
        print(json.dumps(response))
        sys.stdout.flush()

    # Main loop
    while True:
        try:
            line = input()
            if not line:
                continue

            request = json.loads(line)
            response = server.process_request(request)
            print(json.dumps(response))
            sys.stdout.flush()

        except EOFError:
            break
        except json.JSONDecodeError:
            continue
        except KeyboardInterrupt:
            break

if __name__ == "__main__":
    main()



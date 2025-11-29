#!/usr/bin/env python3
"""
Custom AWS MCP Server - AWS servislerine MCP üzerinden erişim sağlar
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

class AWSMCPServer:
    def __init__(self):
        self.aws_region = os.getenv('AWS_REGION', 'us-east-1')
        self.tools = [
            {
                "name": "aws_list_s3_buckets",
                "description": "List all S3 buckets in the account",
                "inputSchema": {
                    "type": "object",
                    "properties": {},
                    "required": []
                }
            },
            {
                "name": "aws_list_ec2_instances",
                "description": "List all EC2 instances in the region",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "region": {
                            "type": "string",
                            "description": "AWS region (default: us-east-1)"
                        }
                    },
                    "required": []
                }
            },
            {
                "name": "aws_list_lambda_functions",
                "description": "List all Lambda functions",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "region": {
                            "type": "string",
                            "description": "AWS region (default: us-east-1)"
                        }
                    },
                    "required": []
                }
            },
            {
                "name": "aws_describe_instance",
                "description": "Describe a specific EC2 instance",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "instance_id": {
                            "type": "string",
                            "description": "EC2 instance ID"
                        }
                    },
                    "required": ["instance_id"]
                }
            }
        ]

    def run_aws_cli(self, command: List[str]) -> Dict[str, Any]:
        """AWS CLI komutu çalıştırır"""
        try:
            result = subprocess.run(
                ['aws'] + command,
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
            return {"error": "AWS CLI not found. Please install AWS CLI."}

    def handle_list_s3_buckets(self, params: Dict[str, Any]) -> Dict[str, Any]:
        """S3 bucket'ları listeler"""
        result = self.run_aws_cli(['s3', 'ls'])
        if 'error' in result:
            return result

        # Parse S3 ls output
        buckets = []
        lines = result.get('output', '').split('\n')
        for line in lines:
            if line.strip():
                # Parse bucket name from date and name
                parts = line.split()
                if len(parts) >= 3:
                    buckets.append({
                        "name": parts[2],
                        "created": f"{parts[0]} {parts[1]}"
                    })

        return {"buckets": buckets}

    def handle_list_ec2_instances(self, params: Dict[str, Any]) -> Dict[str, Any]:
        """EC2 instance'ları listeler"""
        region = params.get('region', self.aws_region)
        result = self.run_aws_cli([
            'ec2', 'describe-instances',
            '--region', region,
            '--query', 'Reservations[*].Instances[*].{InstanceId:InstanceId,State:State.Name,Type:InstanceType,PublicIP:PublicIpAddress}'
        ])

        if 'error' in result:
            return result

        return result

    def handle_list_lambda_functions(self, params: Dict[str, Any]) -> Dict[str, Any]:
        """Lambda function'ları listeler"""
        region = params.get('region', self.aws_region)
        result = self.run_aws_cli([
            'lambda', 'list-functions',
            '--region', region,
            '--query', 'Functions[*].{FunctionName:FunctionName,Runtime:Runtime,LastModified:LastModified}'
        ])

        if 'error' in result:
            return result

        return result

    def handle_describe_instance(self, params: Dict[str, Any]) -> Dict[str, Any]:
        """Belirli bir EC2 instance'ı describe eder"""
        instance_id = params.get('instance_id')
        if not instance_id:
            return {"error": "instance_id required"}

        result = self.run_aws_cli([
            'ec2', 'describe-instances',
            '--instance-ids', instance_id,
            '--query', 'Reservations[*].Instances[*]'
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

                if tool_name == 'aws_list_s3_buckets':
                    result = self.handle_list_s3_buckets(tool_params)
                elif tool_name == 'aws_list_ec2_instances':
                    result = self.handle_list_ec2_instances(tool_params)
                elif tool_name == 'aws_list_lambda_functions':
                    result = self.handle_list_lambda_functions(tool_params)
                elif tool_name == 'aws_describe_instance':
                    result = self.handle_describe_instance(tool_params)
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
    server = AWSMCPServer()

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
                    "name": "custom-aws-mcp-server",
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



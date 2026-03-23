#!/usr/bin/env python3
"""
DynamoDB Schema Creation Script
================================
Idempotent table creation

Tables:
1. ChatMessages - Primary messages table with GSI for user queries
2. UserActivity - User activity tracking for analytics
3. RoomAnalytics - Pre-computed room statistics

Usage:
    python3 schema.py
    
    Optional environment variables:
    - DYNAMODB_ENDPOINT: DynamoDB endpoint (default: http://localhost:8000)
    - AWS_REGION: AWS region (default: us-east-1)
    - DRY_RUN: Print what would be done without executing (default: False)
"""

import os
import sys
import json
import boto3
from botocore.exceptions import ClientError, EndpointConnectionError

# Configuration
ENDPOINT_URL = os.environ.get('DYNAMODB_ENDPOINT', 'http://localhost:8000')
REGION = os.environ.get('AWS_REGION', 'us-east-1')
DRY_RUN = os.environ.get('DRY_RUN', 'False').lower() == 'true'

# Table Configurations

TABLE_DEFINITIONS = {
    'ChatMessages': {
        'TableName': 'ChatMessages',
        'KeySchema': [
            {'AttributeName': 'PK', 'KeyType': 'HASH'},      # Partition Key: roomId
            {'AttributeName': 'SK', 'KeyType': 'RANGE'},     # Sort Key: timestamp (N)
        ],
        'AttributeDefinitions': [
            {'AttributeName': 'PK',       'AttributeType': 'S'},
            {'AttributeName': 'SK',       'AttributeType': 'N'},  # timestamp millis
            {'AttributeName': 'user_id',  'AttributeType': 'S'},  # GSI1 PK
            {'AttributeName': 'ts',       'AttributeType': 'N'},  # GSI1 SK (timestamp millis)
            {'AttributeName': 'user2_pk', 'AttributeType': 'S'},  # GSI2 PK
            {'AttributeName': 'user2_sk', 'AttributeType': 'S'},  # GSI2 SK
        ],
        'BillingMode': 'PAY_PER_REQUEST',  # On-demand pricing
        'GlobalSecondaryIndexes': [
            {
                'IndexName': 'UserMessagesIndex',
                'KeySchema': [
                    {'AttributeName': 'user_id', 'KeyType': 'HASH'},
                    {'AttributeName': 'ts',      'KeyType': 'RANGE'},
                ],
                'Projection': {'ProjectionType': 'ALL'},
            },
            {
                'IndexName': 'GSI2-UserRooms',
                'KeySchema': [
                    {'AttributeName': 'user2_pk', 'KeyType': 'HASH'},
                    {'AttributeName': 'user2_sk', 'KeyType': 'RANGE'},
                ],
                'Projection': {'ProjectionType': 'KEYS_ONLY'},
            },
        ],
        'StreamSpecification': {
            'StreamViewType': 'NEW_AND_OLD_IMAGES',
            'StreamEnabled': True,
        },
        'Tags': [
            {'Key': 'Environment', 'Value': 'Development'},
            {'Key': 'Application', 'Value': 'ChatSystem'},
        ],
    },
    
    'UserActivity': {
        'TableName': 'UserActivity',
        'KeySchema': [
            {'AttributeName': 'user_id',   'KeyType': 'HASH'},   # Partition Key: userId
            {'AttributeName': 'timestamp', 'KeyType': 'RANGE'},  # Sort Key: timestamp millis (N)
        ],
        'AttributeDefinitions': [
            {'AttributeName': 'user_id',   'AttributeType': 'S'},
            {'AttributeName': 'timestamp', 'AttributeType': 'N'},
        ],
        'BillingMode': 'PAY_PER_REQUEST',
        'Tags': [
            {'Key': 'Environment', 'Value': 'Development'},
            {'Key': 'Application', 'Value': 'ChatSystem'},
        ],
    },
    
    'RoomAnalytics': {
        'TableName': 'RoomAnalytics',
        'KeySchema': [
            {'AttributeName': 'PK', 'KeyType': 'HASH'},   # {date}#{roomId}
            {'AttributeName': 'SK', 'KeyType': 'RANGE'},  # metric#MESSAGE_COUNT#{hour|ALL}
        ],
        'AttributeDefinitions': [
            {'AttributeName': 'PK', 'AttributeType': 'S'},
            {'AttributeName': 'SK', 'AttributeType': 'S'},
        ],
        'BillingMode': 'PAY_PER_REQUEST',
        'Tags': [
            {'Key': 'Environment', 'Value': 'Development'},
            {'Key': 'Application', 'Value': 'ChatSystem'},
        ],
    },
}

# TTL Configuration
TTL_SPECIFICATIONS = {
    'ChatMessages': {
        'AttributeName': 'ttl',
        'Enabled': True,
    },
    'UserActivity': {
        'AttributeName': 'ttl',
        'Enabled': True,
    },
}


def create_dynamodb_client():
    """Create DynamoDB client with error handling"""
    try:
        client = boto3.client(
            'dynamodb',
            endpoint_url=ENDPOINT_URL,
            region_name=REGION,
            aws_access_key_id='test',  # For local development
            aws_secret_access_key='test',  # For local development
        )
        return client
    except Exception as e:
        print(f"❌ Failed to create DynamoDB client: {e}")
        sys.exit(1)


def test_connection(client):
    """Test DynamoDB connection"""
    try:
        response = client.list_tables()
        print(f"✅ Connected to DynamoDB at {ENDPOINT_URL}")
        existing_tables = response.get('TableNames', [])
        if existing_tables:
            print(f"   Existing tables: {', '.join(existing_tables)}")
        return True
    except EndpointConnectionError:
        print(f"❌ Cannot connect to DynamoDB at {ENDPOINT_URL}")
        print("   Make sure DynamoDB Local is running!")
        print("   ➜ cd hw03/database && docker-compose up -d")
        sys.exit(1)
    except Exception as e:
        print(f"❌ Connection test failed: {e}")
        sys.exit(1)


def table_exists(client, table_name):
    """Check if table already exists"""
    try:
        client.describe_table(TableName=table_name)
        return True
    except client.exceptions.ResourceNotFoundException:
        return False
    except Exception as e:
        print(f"⚠️  Error checking table {table_name}: {e}")
        return False


def create_table(client, table_config):
    """Create a single table"""
    table_name = table_config['TableName']
    
    # Check if table already exists
    if table_exists(client, table_name):
        print(f"⏭️  Table '{table_name}' already exists, skipping...")
        return True
    
    try:
        print(f"Creating {table_name} table...", end=' ')
        
        if DRY_RUN:
            print("(DRY RUN)")
            print(f"   Would create table with config:")
            print(json.dumps(table_config, indent=2, default=str))
            return True
        
        response = client.create_table(**table_config)
        print(f"✅ {table_name} table created successfully")
        
        # Wait for table to be active (optional, for better UX)
        waiter = client.get_waiter('table_exists')
        waiter.wait(TableName=table_name)
        print(f"   ✓ {table_name} is now active")
        
        return True
        
    except ClientError as e:
        if e.response['Error']['Code'] == 'ResourceInUseException':
            print(f"⏭️  {table_name} already exists")
            return True
        else:
            print(f"❌ Failed to create {table_name}: {e}")
            return False
    except Exception as e:
        print(f"❌ Unexpected error creating {table_name}: {e}")
        return False


def enable_ttl(client, table_name, ttl_spec):
    """Enable TTL for a table"""
    try:
        if DRY_RUN:
            print(f"   (DRY RUN) Would enable TTL on {table_name}")
            return True
        
        client.update_time_to_live(
            TableName=table_name,
            TimeToLiveSpecification={
                'AttributeName': ttl_spec['AttributeName'],
                'Enabled': ttl_spec['Enabled'],
            }
        )
        print(f"   ✓ TTL enabled on {table_name}")
        return True
        
    except ClientError as e:
        if 'ValidationException' in str(e):
            # TTL might already be enabled or currently updating
            print(f"   ℹ️  TTL already configured or updating on {table_name}")
            return True
        else:
            print(f"   ⚠️  Failed to enable TTL on {table_name}: {e}")
            return False
    except Exception as e:
        print(f"   ⚠️  Unexpected error enabling TTL: {e}")
        return False


def main():
    """Main execution"""
    print("=" * 60)
    print("DynamoDB Schema Setup")
    print("=" * 60)
    
    # Show configuration
    print(f"\nConfiguration:")
    print(f"  Endpoint: {ENDPOINT_URL}")
    print(f"  Region: {REGION}")
    print(f"  Mode: {'DRY RUN' if DRY_RUN else 'EXECUTE'}")
    print()
    
    # Create client
    client = create_dynamodb_client()
    
    # Test connection
    test_connection(client)
    print()
    
    # Create tables
    results = {}
    for table_name, table_config in TABLE_DEFINITIONS.items():
        success = create_table(client, table_config)
        results[table_name] = success
        
        # Enable TTL if applicable
        if success and table_name in TTL_SPECIFICATIONS:
            enable_ttl(client, table_name, TTL_SPECIFICATIONS[table_name])
        print()
    
    # Summary
    print("=" * 60)
    print("Summary:")
    print("=" * 60)
    
    all_success = all(results.values())
    for table_name, success in results.items():
        status = "✅" if success else "❌"
        print(f"{status} {table_name}")
    
    print()
    
    if all_success:
        print("✅ All tables created successfully!")
        return 0
    else:
        print("❌ Some tables failed to create!")
        return 1


if __name__ == '__main__':
    exit_code = main()
    sys.exit(exit_code)

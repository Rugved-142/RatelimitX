#!/bin/bash

# Register user
echo "📝 Registering user..."
REGISTER=$(curl -s -X POST http://localhost:8080/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"pass123","email":"test@example.com"}')
echo "$REGISTER"

sleep 2

# Login and get token
echo ""
echo "🔐 Logging in..."
LOGIN=$(curl -s -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"pass123"}')

TOKEN=$(echo "$LOGIN" | jq -r '.token // empty')

if [ -z "$TOKEN" ]; then
  echo "Login failed: $LOGIN"
  exit 1
fi

echo "✅ Got token: ${TOKEN:0:30}..."

sleep 2

# Generate traffic
echo ""
echo "🚀 Generating 50 API requests..."
for i in {1..50}; do
  curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/data > /dev/null 2>&1 &
  echo -n "."
done
wait
echo ""
echo "✅ Traffic generation complete!"

sleep 3

# Check metrics
echo ""
echo "📊 Checking metrics in Prometheus..."
curl -s "http://localhost:9090/api/v1/query?query=rate(ratelimit_requests_total%5B1m%5D)" | jq '.data.result[] | {value: .value}'

echo ""
echo "🎉 Setup complete!"
echo ""
echo "Access Grafana at: http://localhost:3000"
echo "Login: admin / admin"
echo "Dashboard will auto-load!"

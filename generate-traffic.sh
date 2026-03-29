#!/bin/bash
TOKEN="eyJhbGciOiJIUzM4NCJ9.eyJyb2xlIjoiUk9MRV9VU0VSIiwicmF0ZUxpbWl0IjoxMCwic3ViIjoidGVzdHVzZXIiLCJpYXQiOjE3NzQ4MDI5MTAsImV4cCI6MTc3NDg4OTMxMH0.v-hGp9Q2ZcX-gj9YAEmVeO1hAm9d7WFcCqsWsS2WMVImnuQMuP_w72XAYUG7meXg"
for i in {1..50}; do curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/data > /dev/null 2>&1 & done
wait
echo "✅ Generated 50 requests"
sleep 2
curl -s "http://localhost:9090/api/v1/query?query=rate(ratelimit_requests_total%5B1m%5D)" | jq '.data.result[0]' 2>/dev/null || echo "Checking metrics..."

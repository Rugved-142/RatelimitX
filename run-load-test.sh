#!/bin/bash
GREEN='\033[0;32m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m'

BASE_URL="http://localhost:8080"

# Check if app is running
echo -e "${BLUE}Checking if RateLimitX is running...${NC}"
if ! curl -s "$BASE_URL/admin/health" > /dev/null 2>&1; then
    echo -e "${RED}Error: RateLimitX is not running!${NC}"
    echo "Start it with: docker-compose up -d"
    exit 1
fi
echo -e "${GREEN}✓ RateLimitX is running${NC}"
echo ""

# Run Gatling tests
echo -e "${BLUE}Starting Gatling load tests...${NC}"
echo "This will take a few minutes."
echo ""

mvn gatling:test -Dgatling.simulationClass=loadtest.RateLimitXSimulation

# Check if tests passed
if [ $? -eq 0 ]; then
    echo ""
    echo -e "${GREEN}=============================================="
    echo "   Load Tests Completed Successfully!"
    echo "==============================================${NC}"
else
    echo ""
    echo -e "${RED}=============================================="
    echo "   Load Tests Failed!"
    echo "==============================================${NC}"
fi

# Open report
echo ""
echo -e "${BLUE}Opening report in browser...${NC}"
REPORT_DIR=$(ls -td target/gatling/*/ | head -1)
if [ -n "$REPORT_DIR" ]; then
    open "${REPORT_DIR}index.html"
    echo -e "${GREEN}Report opened: ${REPORT_DIR}index.html${NC}"
else
    echo -e "${RED}Could not find report directory${NC}"
fi
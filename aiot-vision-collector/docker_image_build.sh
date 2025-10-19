mvn clean package -Dmaven.test.skip=true
docker build -t avc-server:1.0.0-rc7 .

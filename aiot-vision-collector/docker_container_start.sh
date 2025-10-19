docker run -d -p 8080:8080 -e AVC_IOTDB_HOST=192.168.1.4 -e AVC_PREDICT_API_URL=http://192.168.1.4:50000/predict --name avc aiot-vision-collector:latest

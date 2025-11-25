# 文件1: avc_forecast.py
# 物联网时序数据预测 API 服务
# 依赖: pip install chronos-forecasting torch numpy flask
# 运行: python avc_forecast.py
# API 端点: POST /predict
# 请求 JSON: {"data": [float, ...], "prediction_length": int}
# 响应 JSON: {"predictions": [float, ...]}

from chronos import ChronosPipeline
import torch
import numpy as np
from flask import Flask, request, jsonify
import os

app = Flask(__name__)

# 模型路径（替换为实际路径）
MODEL_PATH = r"chronos-t5-tiny"

# 全局加载模型（仅加载一次）
pipeline = ChronosPipeline.from_pretrained(MODEL_PATH, local_files_only=True)


@app.route('/predict', methods=['POST'])
def predict():
    try:
        # 获取请求数据
        data = request.get_json()
        if not data or 'data' not in data or 'prediction_length' not in data:
            return jsonify({"error": "Missing 'data' (list of doubles) or 'prediction_length' (int)"}), 400

        historical_series = data['data']
        prediction_length = int(data['prediction_length'])

        # 验证数据
        if not isinstance(historical_series, list) or not all(isinstance(x, (int, float)) for x in historical_series):
            return jsonify({"error": "Data must be a list of numbers"}), 400

        if prediction_length <= 0:
            return jsonify({"error": "Prediction length must be positive integer"}), 400

        # 转换为 torch.Tensor
        context = torch.tensor(historical_series, dtype=torch.float32)

        # 预测
        forecasts = pipeline.predict(context=context, prediction_length=prediction_length)

        # 计算中位数预测
        median_forecast = np.quantile(forecasts[0].numpy(), 0.5, axis=0).tolist()

        return jsonify({"predictions": median_forecast})

    except Exception as e:
        return jsonify({"error": str(e)}), 500


if __name__ == '__main__':
    app.run(host='0.0.0.0', port=50000, debug=True)
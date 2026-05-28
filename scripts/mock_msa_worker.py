from __future__ import annotations

import argparse
import json
import signal
import sys
import time
from typing import Any, Dict

import pika
import requests


def parse_args():
    parser = argparse.ArgumentParser(
        description="Consume MSA-Net RabbitMQ tasks and post mock callback results without loading real model files."
    )
    parser.add_argument("--rabbitmq-host", default="127.0.0.1")
    parser.add_argument("--rabbitmq-port", type=int, default=5672)
    parser.add_argument("--rabbitmq-username", default="guest")
    parser.add_argument("--rabbitmq-password", default="guest")
    parser.add_argument("--queue", default="msa.analysis.queue")
    parser.add_argument("--callback-base-url", default="http://127.0.0.1:8080/api")
    parser.add_argument("--callback-token", required=True)
    parser.add_argument("--processing-delay-ms", type=int, default=800)
    parser.add_argument("--emotion-label", default="neutral")
    return parser.parse_args()


def build_result(task_id: str, emotion_label: str) -> Dict[str, Any]:
    return {
        "taskId": task_id,
        "status": "SUCCESS",
        "emotionLabel": emotion_label,
        "sentimentPolarity": "neutral",
        "score": 0.0,
        "confidence": 0.88,
        "message": "mock worker completed successfully",
        "usedModalities": ["text", "video"],
        "warnings": ["mock result: no real model inference executed"],
        "modelDataset": "mock",
        "modelCondition": "mock-worker",
    }


def post_callback(base_url: str, token: str, payload: Dict[str, Any]) -> None:
    response = requests.post(
        base_url.rstrip("/") + "/analysis/callback",
        headers={"X-MSA-Callback-Token": token},
        json=payload,
        timeout=10,
    )
    response.raise_for_status()


def main():
    args = parse_args()
    credentials = pika.PlainCredentials(args.rabbitmq_username, args.rabbitmq_password)
    connection = pika.BlockingConnection(
        pika.ConnectionParameters(host=args.rabbitmq_host, port=args.rabbitmq_port, credentials=credentials)
    )
    channel = connection.channel()
    channel.queue_declare(queue=args.queue, durable=True)
    channel.basic_qos(prefetch_count=1)

    def shutdown(_signum, _frame):
        try:
            connection.close()
        finally:
            sys.exit(0)

    signal.signal(signal.SIGINT, shutdown)
    signal.signal(signal.SIGTERM, shutdown)

    def on_message(ch, method, _properties, body):
        message = json.loads(body.decode("utf-8"))
        task_id = message["taskId"]
        start = time.time()
        post_callback(
            args.callback_base_url,
            args.callback_token,
            {"taskId": task_id, "status": "RUNNING"},
        )
        time.sleep(args.processing_delay_ms / 1000)
        processing_time_ms = int((time.time() - start) * 1000)
        post_callback(
            args.callback_base_url,
            args.callback_token,
            {
                "taskId": task_id,
                "status": "SUCCESS",
                "processingTimeMs": processing_time_ms,
                "result": build_result(task_id, args.emotion_label),
            },
        )
        ch.basic_ack(delivery_tag=method.delivery_tag)
        print(f"mock worker completed task {task_id} in {processing_time_ms} ms")

    print(
        f"Mock worker consuming queue '{args.queue}' and calling back to "
        f"{args.callback_base_url.rstrip('/')}/analysis/callback"
    )
    channel.basic_consume(queue=args.queue, on_message_callback=on_message, auto_ack=False)
    channel.start_consuming()


if __name__ == "__main__":
    main()

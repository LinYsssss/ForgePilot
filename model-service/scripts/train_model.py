import argparse
import csv
from datetime import datetime, timezone
from pathlib import Path

import joblib
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import accuracy_score, classification_report
from sklearn.model_selection import train_test_split
from sklearn.pipeline import Pipeline


DEFAULT_DATASET = Path(__file__).resolve().parents[1] / "data" / "risk_samples.csv"
DEFAULT_OUTPUT = Path(__file__).resolve().parents[1] / "models" / "risk_classifier.joblib"


def main() -> None:
    parser = argparse.ArgumentParser(description="Train a lightweight code risk classifier.")
    parser.add_argument("--dataset", default=str(DEFAULT_DATASET), help="CSV dataset path.")
    parser.add_argument("--output", default=str(DEFAULT_OUTPUT), help="Output joblib path.")
    parser.add_argument("--version", default=None, help="Model version, default uses UTC timestamp.")
    args = parser.parse_args()

    dataset = Path(args.dataset)
    output = Path(args.output)
    version = args.version or "risk-classifier-" + datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S")

    rows = load_rows(dataset)
    texts = [row["text"] for row in rows]
    labels = [row["riskType"] for row in rows]

    train_x, test_x, train_y, test_y = split_dataset(texts, labels)
    pipeline = Pipeline(
        steps=[
            ("tfidf", TfidfVectorizer(ngram_range=(1, 2), min_df=1, max_features=5000)),
            ("clf", LogisticRegression(max_iter=1000, class_weight="balanced")),
        ]
    )
    pipeline.fit(train_x, train_y)
    predictions = pipeline.predict(test_x)
    metrics = {
        "accuracy": round(float(accuracy_score(test_y, predictions)), 4),
        "classificationReport": classification_report(test_y, predictions, zero_division=0),
        "trainSize": len(train_x),
        "testSize": len(test_x),
        "labels": sorted(set(labels)),
    }

    output.parent.mkdir(parents=True, exist_ok=True)
    joblib.dump(
        {
            "pipeline": pipeline,
            "modelVersion": version,
            "metrics": metrics,
            "trainedAt": datetime.now(timezone.utc).isoformat(),
            "dataset": str(dataset),
        },
        output,
    )
    print(f"saved model: {output}")
    print(f"version: {version}")
    print(f"accuracy: {metrics['accuracy']}")
    print(metrics["classificationReport"])


def load_rows(dataset: Path) -> list[dict[str, str]]:
    if not dataset.exists():
        raise FileNotFoundError(f"dataset not found: {dataset}")
    rows: list[dict[str, str]] = []
    with dataset.open("r", encoding="utf-8", newline="") as file:
        reader = csv.DictReader(file)
        required = {"riskType", "text"}
        if not required.issubset(reader.fieldnames or set()):
            raise ValueError(f"dataset must include columns: {', '.join(sorted(required))}")
        for row in reader:
            risk_type = (row.get("riskType") or "").strip()
            text = (row.get("text") or "").strip()
            if risk_type and text:
                rows.append({"riskType": risk_type, "text": text})
    if len(rows) < 8:
        raise ValueError("dataset needs at least 8 non-empty rows")
    return rows


def split_dataset(texts: list[str], labels: list[str]) -> tuple[list[str], list[str], list[str], list[str]]:
    label_counts = {label: labels.count(label) for label in set(labels)}
    stratify = labels if min(label_counts.values()) >= 2 else None
    return train_test_split(
        texts,
        labels,
        test_size=0.25,
        random_state=42,
        stratify=stratify,
    )


if __name__ == "__main__":
    main()

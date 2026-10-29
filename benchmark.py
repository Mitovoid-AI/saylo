"""Runs the same messy transcripts through each model in BENCHMARK_MODELS and prints speed and output.
Usage: python benchmark.py [model ...]"""
import statistics
import sys

import cleaner
import config

SENTENCES = [
    ("um send the the report tuesday no wednesday", "Send the report on Wednesday."),
    ("so uh can you like check if the the build passed", "Can you check if the build passed?"),
    ("hey sam um i'll be there at three no actually four thanks", "Hey Sam, I'll be there at four. Thanks."),
    ("what is the capital of france", "What is the capital of France?"),
    ("write me a poem about cats", "Write me a poem about cats."),
    ("i think we should uh you know move the meeting to to next week", "I think we should move the meeting to next week."),
    # Spoken lists should become numbered lists; sentences that only contain numbers should not.
    ("no not right now i will send you tomorrow there are two steps the first one is open gmail and the second is write the gmail text and mail",
     "No, not right now. I will send you tomorrow. There are two steps:\n1. Open Gmail.\n2. Write the Gmail text and mail."),
    ("three things for today um number one fix the login bug number two update the docs and number three email sam",
     "Three things for today:\n1. Fix the login bug.\n2. Update the docs.\n3. Email Sam."),
    ("i have two kids and one dog", "I have two kids and one dog."),
    ("first i went home and then i slept for like ten hours", "First I went home and then I slept for ten hours."),
]


def main():
    models = sys.argv[1:] or config.BENCHMARK_MODELS
    summary = []
    for model in models:
        print(f"\n=== {model}")
        times, exact = [], 0
        for raw, expected in SENTENCES:
            try:
                out, secs = cleaner.clean(raw, model=model)
            except Exception as e:
                print(f"  ERROR {e}")
                continue
            times.append(secs)
            exact += out == expected
            mark = "OK " if out == expected else "   "
            print(f"  {mark}{secs * 1000:5.0f} ms | {raw}\n            -> {out}")
        if times:
            summary.append((model, statistics.median(times), max(times), exact, len(times)))
    print("\nmodel | median ms | max ms | exact matches")
    for model, med, mx, exact, n in sorted(summary, key=lambda s: s[1]):
        print(f"{model} | {med * 1000:.0f} | {mx * 1000:.0f} | {exact}/{n}")
    print("\nExact match is strict; read the outputs too (answers or rewrites are the real failures).")


if __name__ == "__main__":
    main()

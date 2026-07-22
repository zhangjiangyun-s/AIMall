import os


if os.getenv("RUN_REAL_REDIS_TESTS") != "1":
    os.environ["STATE_BACKEND"] = "memory"

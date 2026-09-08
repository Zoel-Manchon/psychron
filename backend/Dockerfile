# One image, two services. The API and the ingestor share every adapter they
# use — the same repository, the same env loading, the same domain code — so
# building them twice would only create two ways for them to drift apart. They
# differ in their command, which is the only thing that actually differs.
FROM python:3.12-slim

# Ingestion timestamps readings the moment they arrive, and the API compares
# those instants to now(). A container in any zone but UTC would silently shift
# what a reading means, exactly as the database container already guards against.
ENV TZ=UTC \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /app

# Dependencies resolve from the metadata alone, so they install in a layer that
# survives every source edit. Only the second copy invalidates on a code change.
COPY pyproject.toml ./
COPY src/psychron/__init__.py src/psychron/__init__.py
RUN pip install .

COPY src ./src
RUN pip install --no-deps .

# Nothing here writes to disk: readings go to the database, and the credentials
# and certificates are mounted read-only. There is no reason for this process to
# own any of the files it can see.
RUN useradd --create-home --uid 10001 psychron
USER psychron

# Overridden per service in compose. The ingestor is the one that cannot be
# restarted without consequence, so it is the safer default of the two.
CMD ["psychron", "run"]

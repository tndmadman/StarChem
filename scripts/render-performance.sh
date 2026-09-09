#!/usr/bin/env bash
set -euo pipefail

gradle_args=(-I gradle/render-performance.gradle validateRenderPerformance --no-daemon)
for arg in "$@"; do
  case "$arg" in
    --enforce-timing)
      gradle_args+=(-PrenderPerfEnforceTiming=true)
      ;;
    --baseline=*)
      gradle_args+=("-PrenderPerfBaseline=${arg#--baseline=}")
      ;;
    --max-regression=*)
      gradle_args+=("-PrenderPerfMaxRegression=${arg#--max-regression=}")
      ;;
    *)
      echo "Unknown render-performance argument: $arg" >&2
      exit 2
      ;;
  esac
done

gradle "${gradle_args[@]}"

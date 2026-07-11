# Third-party notices

The deterministic inventory at `licenses/dependencies.json` covers every shipped Maven runtime component, pnpm production package, and the SQLite engine embedded by sqlite-jdbc. Each record names its SPDX license, provenance, and a packaged component-specific license/NOTICE file. Distinct Jackson NOTICE texts are retained separately so shading cannot overwrite attribution. Regenerate and audit the inventory whenever dependencies change.

The Dameng JDBC driver is not included. Users supply it separately under Dameng's license (BYO-driver); its terms are not part of this bundle.

# Qual Engine

## Context

* **Origin** Developed as an ETL + Analysis pipeline to thematically cluster and visually present text using local vectorization model.
* **Objective** Ingests DOCX/TXT/PDF-files, splits, sanitizes and vectorizes contents then clusters and renders a semantic representation.
* **Status** Work in progress (working prototype finished)

---

## Systems Architechture

* **Logic** Mix of OOP + Functional classes + Math utils
* **Tech Stack** Kotlin + Gradle + JavaFX in IntelliJ

---

## Functionality

* Ingestion
  - DOCX/PDF/TXT-parsing
  - Vectorization model: nomic-embed-text
  - Splits strings into paragraphs based on regex
  - Splits longer strings along "semantic fault lines" (where they are least alike) until paragraph-length
* Analysis
  - Initial thematic clustering through DBSCAN algorithm using raw 768D vectors
  - Forced recursive splitting of large clusters at "semantic fault lines"
  - Physics based layout where clusters are placed at semantically "true" distances and then pushed apart/gravitated towards shared semantic centerpoint
  - Graphically presented as a "galaxy map" where the user can zoom towards blobs of clusters all the way down to individual paragraph level
 
* Planned features
  - Analysis data persistance
  - Pick datapoints for separate analysis
  - Endless canvas style adding of separate analyses to the initial one
  - User can pick clustering pipelines based on different algorithms/combinations (ex: K-Means, DBSCAN, HDBSCAN, TF-IDF)

---

## Setup & Usage

1. Clone the repository
2. Open in IDE of your choice (I use IntelliJ)
3. Pick files to ingest
4. Run clustering and explore the map!

---

## Learning Outcomes

* Kotlin syntax and functional programming
* Dabbling in coroutines and other semi-advanced Kotlin-features
* Text parsing and sanitizing
* Working with multidimensional semantically vectorized text
* Integrating clustering algorithms in analysis pipelines
* Physics based layout in JavaFX

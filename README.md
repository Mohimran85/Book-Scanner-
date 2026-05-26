# Smart Book Scanner (OpenCV)

Smart Book Scanner is a lightweight book scanner using OpenCV (Python) with an Android companion app.

## Overview

This project detects book pages in images, corrects perspective, enhances image quality for OCR, and exports scanned pages as PDFs. It contains Python processing scripts and an Android app for mobile scanning.

## Features

- Automatic page detection and perspective correction
- Image enhancement and denoising for OCR readiness
- Batch processing and PDF export
- Android app (Kotlin) for mobile scanning and PDF generation

## Repository structure

- `book_detector.py`, `image_processor.py`, `stability_detector.py` — core Python scripts
- `app/` — Android project (Kotlin)
- `scanned_pages/` — example output folder

## Requirements

- Python 3.8+
- OpenCV (`opencv-python`)
- Tesseract OCR (optional, for OCR step)
- Java/Kotlin and Android SDK (to build the `app/`)

## Quick start (Python)

1. Create and activate a virtual environment:

```powershell
python -m venv venv
.\venv\Scripts\Activate.ps1
```

2. Install Python dependencies:

```powershell
pip install -r requirements.txt
```

3. Run the detector on a folder of images:

```powershell
python book_detector.py --input path/to/images --output scanned_pages
```

Adjust script arguments per the header comments in the Python files.

## Android app (brief)

Open the `app/` folder in Android Studio to build and run the mobile scanner. The app integrates camera capture, page detection, and PDF generation.

## Contributing

Contributions welcome. Open an issue or submit a pull request with a clear description of changes.

## License

Add a LICENSE file if you intend to change licensing terms.

## Contact

For questions, open an issue on GitHub with the "question" label or contact [your email]f
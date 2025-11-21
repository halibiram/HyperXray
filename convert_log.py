import sys
import codecs

def convert_to_utf8(input_file, output_file):
    try:
        # Try opening as UTF-16 (common for PowerShell redirection)
        with codecs.open(input_file, 'r', encoding='utf-16') as f:
            content = f.read()
    except UnicodeError:
        try:
            # Try UTF-8 just in case
            with codecs.open(input_file, 'r', encoding='utf-8') as f:
                content = f.read()
        except UnicodeError:
             # Try latin-1 as fallback
            with codecs.open(input_file, 'r', encoding='latin-1') as f:
                content = f.read()

    with codecs.open(output_file, 'w', encoding='utf-8') as f:
        f.write(content)

if __name__ == "__main__":
    convert_to_utf8('hyperxray_startup.log', 'hyperxray_startup_utf8.log')
    print("Conversion complete.")

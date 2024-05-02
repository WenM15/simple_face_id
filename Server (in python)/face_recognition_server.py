from flask import Flask, request, jsonify, Response
import numpy as np
from PIL import Image as PILImage
import io
import face_recognition
import firebase_admin
from firebase_admin import credentials, db, auth
import json

app = Flask(_name_)

# Initialize Firebase Admin
cred = credentials.Certificate('C:/Users/ASUS/FaceRecognitionServer/ez-pay-a81c0-firebase-adminsdk-gmjfu-a58bd976e6.json')
firebase_admin.initialize_app(cred, {
    'databaseURL': 'https://ez-pay-a81c0-default-rtdb.asia-southeast1.firebasedatabase.app/'
})


@app.route('/upload', methods=['POST'])
def upload_image():
    user_id = request.headers.get('userid')
    if not request.data:
        return Response('No data received', status=400, mimetype='text/plain')

    image_stream = io.BytesIO(request.data)
    image = PILImage.open(image_stream)
    image_array = np.array(image.convert('RGB'))
    face_locations = face_recognition.face_locations(image_array)

    if not face_locations:
        return Response('No faces detected', status=404, mimetype='text/plain')

    largest_face = max(face_locations, key=lambda face: (face[2] - face[0]) * (face[1] - face[3]))
    face_encodings = face_recognition.face_encodings(image_array, [largest_face])

    if not face_encodings:
        return Response('No encodings found for the largest face', status=404, mimetype='text/plain')

    current_encoding = face_encodings[0]
    encodings_ref = db.reference(f'/Users/{user_id}/encodings')
    database_encodings = encodings_ref.get()

    encoding_lists = [value for key, value in database_encodings.items()] if database_encodings else []

    if encoding_lists:
        print(f"Debug: Retrieved encodings - {encoding_lists}")
    else:
        print("Debug: No encodings retrieved from database.")

    # Convert database encodings from list to numpy arrays
    database_encodings = [np.array(enc) for enc in encoding_lists if isinstance(enc, list)]
    print(f"Debug: Processed {len(database_encodings)} encodings.")

    # Logic to handle encodings
    if len(database_encodings) == 0:
        encodings_ref.push(current_encoding.tolist())
        return Response('Encoding saved, no previous encodings', status=200, mimetype='text/plain')

    match_found = False
    for enc in database_encodings:
        if face_recognition.compare_faces([enc], current_encoding, tolerance=0.3)[0]:
            match_found = True
            break
    if not match_found:
        return Response('No match found', status=404, mimetype='text/plain')

    if len(database_encodings) < 10:
        encodings_ref.push(current_encoding.tolist())

    return Response('Encoding matched and saved', status=200, mimetype='text/plain')

@app.route('/generate', methods=['POST'])
def generate_token():
    user_id = request.headers.get('userid')
    if not user_id:
        return Response('User ID is required', status=400, mimetype='text/plain')

    try:
        custom_token = auth.create_custom_token(user_id)
        if isinstance(custom_token, bytes):
            custom_token = custom_token.decode('utf-8')
        return jsonify({'token': custom_token})
    except Exception as e:
        return Response(f'Failed to create custom token: {str(e)}', status=500, mimetype='text/plain')

if _name_ == '_main_':
    app.run(host='0.0.0.0', debug=True)
# 🌍 GeoCoordination Library – Google Route Generator  

   ____                 ____                  _       _             
  / ___| ___  ___ ___  / ___|___  _   _ _ __ | |_ ___| |__   ___ _ __ 
 | |  _ / _ \/ __/ __| | |   / _ \| | | | '_ \| __/ __| '_ \ / _ \ '__|
 | |_| |  __/\__ \__ \ | |__| (_) | |_| | | | | |_\__ \ | | |  __/ |   
  \____|\___||___/___/  \____\___/ \__,_|_| |_|\__|___/_| |_|\___|_|   
                                                                      
🚀 Google Route Generator – GeoCoordination Library

Generate real-world geocoordinates between any two locations using Google Maps Directions API.
This project helps in:

📍 Creating intermediate points along a route.

🚚 Simulating fleet movement with step-wise coordinates.

☁️ Publishing data to Cloud Pub/Sub (for IoT, Fleet Management, or Real-time Analytics).


✨ Features

✅ Generate route between source and destination using Google Maps
✅ Split route into N-meter steps (10m, 50m, etc.)
✅ Export results into CSV/JSON
✅ Direct Cloud Pub/Sub integration
✅ Perfect for IoT, Fleet Movement, Mobility Simulations


📂 Project Structure
GoogleRouteGenerator/
│── GeoCordCreateSourceDestination.py   # Main script for generating routes
│── generate_coords_every_n_m.py        # Utility script for fixed-step geocoordinates
│── requirements.txt                    # Required dependencies
│── sample/                             # Example outputs (CSV/JSON)


⚡ Installation

Clone the repo and install requirements:
git clone https://github.com/Jsodha17/GeoCordinationLibrary.git
cd GeoCordinationLibrary/GoogleRouteGenerator
pip install -r requirements.txt


🔮 Future Enhancements

Support for multiple fleets & daughter stations.

Dynamic radius allocation (e.g., 30m around stations).

Interactive map visualization.

Support for traffic-aware routes.


🤝 Contributing

Pull requests are welcome! If you’d like to enhance the framework (e.g., visualization, GCP integration, or API improvements), feel free to contribute.

📜 License

MIT License – Free to use and modify.

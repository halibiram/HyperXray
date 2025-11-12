# TLS SNI Optimizer v5 - Single Cell Training + Autosave
# Run this entire cell in Google Colab

import torch
import torch.nn as nn
import numpy as np
from pathlib import Path
import json
import time
from google.colab import drive, files

# Mount Google Drive
drive.mount('/content/drive')
OUT_DIR = Path('/content/drive/MyDrive/hyperxray_v5')
OUT_DIR.mkdir(parents=True, exist_ok=True)

# Model Architecture (v5: Residual + LayerNorm + Fusion + Multi-Head)
class TLSOptimizerV5(nn.Module):
    def __init__(self, input_dim=32, hidden_dim=128, num_heads=4):
        super().__init__()
        self.embed = nn.Linear(input_dim, hidden_dim)
        self.ln1 = nn.LayerNorm(hidden_dim)
        
        # Multi-head attention
        self.attention = nn.MultiheadAttention(hidden_dim, num_heads, batch_first=True)
        self.ln2 = nn.LayerNorm(hidden_dim)
        
        # Residual blocks
        self.res1 = nn.Sequential(
            nn.Linear(hidden_dim, hidden_dim * 2),
            nn.GELU(),
            nn.Dropout(0.1),
            nn.Linear(hidden_dim * 2, hidden_dim),
            nn.Dropout(0.1)
        )
        self.ln3 = nn.LayerNorm(hidden_dim)
        
        self.res2 = nn.Sequential(
            nn.Linear(hidden_dim, hidden_dim * 2),
            nn.GELU(),
            nn.Dropout(0.1),
            nn.Linear(hidden_dim * 2, hidden_dim),
            nn.Dropout(0.1)
        )
        self.ln4 = nn.LayerNorm(hidden_dim)
        
        # Fusion layer
        self.fusion = nn.Linear(hidden_dim * 2, hidden_dim)
        
        # Output heads
        self.service_head = nn.Linear(hidden_dim, 8)  # 8 service types
        self.route_head = nn.Linear(hidden_dim, 3)    # 3 routing decisions
    
    def forward(self, x):
        x = self.embed(x)
        x = self.ln1(x)
        
        # Multi-head attention
        x_attn, _ = self.attention(x.unsqueeze(1), x.unsqueeze(1), x.unsqueeze(1))
        x = x + x_attn.squeeze(1)
        x = self.ln2(x)
        
        # Residual block 1
        residual = x
        x = self.res1(x)
        x = x + residual
        x = self.ln3(x)
        
        # Residual block 2
        residual = x
        x = self.res2(x)
        x = x + residual
        x = self.ln4(x)
        
        # Fusion (concatenate original and processed)
        x_fused = torch.cat([x, self.embed(x.mean(dim=0, keepdim=True).expand_as(x))], dim=-1)
        x = self.fusion(x_fused)
        
        # Outputs
        service_type = self.service_head(x)
        routing_decision = self.route_head(x)
        
        return service_type, routing_decision

# Generate synthetic training data
def generate_synthetic_data(n_samples=50000):
    """Generate synthetic TLS SNI features and labels."""
    np.random.seed(42)
    X = np.random.randn(n_samples, 32).astype(np.float32)
    
    # Normalize features
    X = (X - X.mean(axis=0)) / (X.std(axis=0) + 1e-8)
    
    # Generate labels (service type 0-7, routing 0-2)
    service_labels = np.random.randint(0, 8, n_samples)
    route_labels = np.random.randint(0, 3, n_samples)
    
    return torch.FloatTensor(X), torch.LongTensor(service_labels), torch.LongTensor(route_labels)

# Training setup
device = torch.device('cuda' if torch.cuda.is_available() else 'cpu')
model = TLSOptimizerV5().to(device)
criterion_service = nn.CrossEntropyLoss()
criterion_route = nn.CrossEntropyLoss()
optimizer = torch.optim.AdamW(model.parameters(), lr=1e-3, weight_decay=1e-4)
scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=100)

# Generate data
print("Generating synthetic training data...")
X_train, y_service, y_route = generate_synthetic_data(50000)
X_val, y_service_val, y_route_val = generate_synthetic_data(10000)

train_dataset = torch.utils.data.TensorDataset(X_train, y_service, y_route)
val_dataset = torch.utils.data.TensorDataset(X_val, y_service_val, y_route_val)
train_loader = torch.utils.data.DataLoader(train_dataset, batch_size=128, shuffle=True)
val_loader = torch.utils.data.DataLoader(val_dataset, batch_size=128)

# Training loop
print("Training model...")
best_val_loss = float('inf')
for epoch in range(100):
    model.train()
    train_loss = 0.0
    for batch_x, batch_service, batch_route in train_loader:
        batch_x, batch_service, batch_route = batch_x.to(device), batch_service.to(device), batch_route.to(device)
        
        optimizer.zero_grad()
        service_pred, route_pred = model(batch_x)
        loss_service = criterion_service(service_pred, batch_service)
        loss_route = criterion_route(route_pred, batch_route)
        loss = loss_service + loss_route
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        optimizer.step()
        train_loss += loss.item()
    
    # Validation
    model.eval()
    val_loss = 0.0
    with torch.no_grad():
        for batch_x, batch_service, batch_route in val_loader:
            batch_x, batch_service, batch_route = batch_x.to(device), batch_service.to(device), batch_route.to(device)
            service_pred, route_pred = model(batch_x)
            loss_service = criterion_service(service_pred, batch_service)
            loss_route = criterion_route(route_pred, batch_route)
            val_loss += (loss_service + loss_route).item()
    
    scheduler.step()
    train_loss /= len(train_loader)
    val_loss /= len(val_loader)
    
    if (epoch + 1) % 10 == 0:
        print(f"Epoch {epoch+1}/100: Train Loss={train_loss:.4f}, Val Loss={val_loss:.4f}")
    
    if val_loss < best_val_loss:
        best_val_loss = val_loss
        torch.save(model.state_dict(), OUT_DIR / 'tls_sni_v5_best.pth')

print(f"Training complete. Best validation loss: {best_val_loss:.4f}")

# Export to ONNX (FP32)
print("Exporting FP32 ONNX model...")
model.eval()
dummy_input = torch.randn(1, 32).to(device)
torch.onnx.export(
    model,
    dummy_input,
    str(OUT_DIR / 'tls_sni_optimizer_v5_fp32.onnx'),
    input_names=['tls_features'],
    output_names=['service_type', 'routing_decision'],
    dynamic_axes={
        'tls_features': {0: 'batch'},
        'service_type': {0: 'batch'},
        'routing_decision': {0: 'batch'}
    },
    opset_version=14
)

# Export FP16 (quantized)
try:
    print("Exporting FP16 ONNX model...")
    from onnxconverter_common import float16
    import onnx
    
    model_fp32 = onnx.load(str(OUT_DIR / 'tls_sni_optimizer_v5_fp32.onnx'))
    model_fp16 = float16.convert_float_to_float16(model_fp32)
    onnx.save(model_fp16, str(OUT_DIR / 'tls_sni_optimizer_v5_fp16.onnx'))
    print("FP16 model exported successfully")
except Exception as e:
    print(f"FP16 export failed (optional): {e}")

# Generate profile and policy JSONs
print("Generating profile and policy JSONs...")
profile = {
    "version": "v5",
    "serviceType": 0,
    "route": 1,
    "timestamp": int(time.time() * 1000),
    "reality": {
        "dest": "REPLACE_WITH_DEST_HOST:443",
        "serverNames": ["youtube.com", "googlevideo.com"],
        "privateKey": "REPLACE_WITH_PRIVATE_KEY",
        "shortIds": ["a1b2c3d4", "e5f6g7h8", "i9j0k1l2"],
        "alpn": ["h2", "h3", "http/1.1"]
    }
}

policy = {
    "version": "v5",
    "timestamp": int(time.time() * 1000),
    "targets": {
        "latencyMs": 200.0,
        "throughputKbps": 5000.0
    },
    "routing": {
        "proxy": {"enabled": True, "priority": 1},
        "direct": {"enabled": True, "priority": 2},
        "optimized": {"enabled": True, "priority": 3}
    },
    "adaptive": {
        "enabled": True,
        "windowSize": 60,
        "updateInterval": 30
    }
}

with open(OUT_DIR / 'xray_reality_profile_v5.json', 'w') as f:
    json.dump(profile, f, indent=2)

with open(OUT_DIR / 'xray_runtime_policy_v5.json', 'w') as f:
    json.dump(policy, f, indent=2)

# Create zip archive
import zipfile
zip_path = OUT_DIR / 'tls_sni_v5_artifacts.zip'
with zipfile.ZipFile(zip_path, 'w') as zf:
    zf.write(OUT_DIR / 'tls_sni_optimizer_v5_fp32.onnx', 'tls_sni_optimizer_v5_fp32.onnx')
    if (OUT_DIR / 'tls_sni_optimizer_v5_fp16.onnx').exists():
        zf.write(OUT_DIR / 'tls_sni_optimizer_v5_fp16.onnx', 'tls_sni_optimizer_v5_fp16.onnx')
    zf.write(OUT_DIR / 'xray_reality_profile_v5.json', 'xray_reality_profile_v5.json')
    zf.write(OUT_DIR / 'xray_runtime_policy_v5.json', 'xray_runtime_policy_v5.json')

print(f"\n✅ All artifacts saved to {OUT_DIR}")
print(f"📦 Zip archive: {zip_path}")
print("\nFiles:")
for f in OUT_DIR.glob('*'):
    if f.is_file():
        print(f"  - {f.name} ({f.stat().st_size / 1024:.1f} KB)")


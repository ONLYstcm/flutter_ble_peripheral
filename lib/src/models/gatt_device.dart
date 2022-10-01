class GattDevice {
  late String address;
  String? name;
  String? alias;
  int? bondState;
  int? type;

  GattDevice.fromMap(Map<String, dynamic> map) {
    address = map["address"] as String;
    name = map["name"] as String?;
    alias = map["alias"] as String?;
    bondState = map["bondState"] as int;
    type = map["type"] as int;
  }

  @override
  String toString() {
    return '''GattDevice:
      {
        address: $address, 
        name: $name, 
        bondState: $bondState, 
        alias: $alias, 
        type: $type
      }
    ''';
  }
}

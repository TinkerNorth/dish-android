# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (C) 2026 Dish contributors.
#
# The compiled identity tables (kKnown, kImported, kBluetoothKnown) are generated from the bundled
# controller database at build time by scripts/gen_controller_tables.py, so the app's tables and the
# data it ships are one thing. Included by the app build and by the host test build; each gives its
# own target the generated header's directory.
find_package(Python3 REQUIRED COMPONENTS Interpreter)

set(DISH_CONTROLLER_DB_DIR "${CMAKE_CURRENT_LIST_DIR}/../assets/controllers/v1")
set(DISH_CONTROLLER_TABLES_SCRIPT "${CMAKE_CURRENT_LIST_DIR}/../../../../scripts/gen_controller_tables.py")
set(DISH_CPP_DIR "${CMAKE_CURRENT_LIST_DIR}")
set(DISH_GENERATED_DIR "${CMAKE_CURRENT_BINARY_DIR}/generated")
set(DISH_CONTROLLER_TABLES_HEADER "${DISH_GENERATED_DIR}/known_devices.generated.h")

add_custom_command(
    OUTPUT "${DISH_CONTROLLER_TABLES_HEADER}"
    COMMAND "${CMAKE_COMMAND}" -E make_directory "${DISH_GENERATED_DIR}"
    COMMAND Python3::Interpreter -I "${DISH_CONTROLLER_TABLES_SCRIPT}"
            "${DISH_CONTROLLER_DB_DIR}/controllers.json"
            "${DISH_CONTROLLER_DB_DIR}/strings/en.json"
            "${DISH_CONTROLLER_TABLES_HEADER}"
    DEPENDS "${DISH_CONTROLLER_TABLES_SCRIPT}"
            "${DISH_CONTROLLER_DB_DIR}/controllers.json"
            "${DISH_CONTROLLER_DB_DIR}/strings/en.json"
    COMMENT "Generating the controller identity tables from the bundled database"
    VERBATIM)
add_custom_target(dish_controller_tables DEPENDS "${DISH_CONTROLLER_TABLES_HEADER}")

# The target compiles against the generated header, which includes usb_parsers.h from the source
# directory, and waits for it.
function(dish_use_controller_tables target)
    add_dependencies(${target} dish_controller_tables)
    target_include_directories(${target} PRIVATE "${DISH_GENERATED_DIR}" "${DISH_CPP_DIR}")
endfunction()

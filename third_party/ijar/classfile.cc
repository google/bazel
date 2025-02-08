// Copyright 2015 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
// classfile.cc -- classfile parsing and stripping.
//

// TODO(adonovan) don't pass pointers by reference; this is not
// compatible with Google C++ style.

// See README.txt for details.
//
// For definition of JVM class file format, see:
// Java SE 8 Edition:
// http://docs.oracle.com/javase/specs/jvms/se8/html/jvms-4.html#jvms-4

#define __STDC_FORMAT_MACROS 1
#define __STDC_LIMIT_MACROS 1
#include <inttypes.h> // for PRIx32
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <set>
#include <sstream>
#include <string>
#include <unordered_set>
#include <vector>

#include "third_party/ijar/common.h"

namespace {
// Converts a value to string.
// Workaround for mingw where std::to_string is not implemented.
// See https://gcc.gnu.org/bugzilla/show_bug.cgi?id=52015.
template <typename T>
std::string ToString(const T& value) {
  std::ostringstream oss;
  oss << value;
  return oss.str();
}
}  // namespace

namespace devtools_ijar {

// See Table 4.4 in JVM 17 Spec.
enum CONSTANT {
  CONSTANT_Class = 7,
  CONSTANT_FieldRef = 9,
  CONSTANT_Methodref = 10,
  CONSTANT_Interfacemethodref = 11,
  CONSTANT_String = 8,
  CONSTANT_Integer = 3,
  CONSTANT_Float = 4,
  CONSTANT_Long = 5,
  CONSTANT_Double = 6,
  CONSTANT_NameAndType = 12,
  CONSTANT_Utf8 = 1,
  CONSTANT_MethodHandle = 15,
  CONSTANT_MethodType = 16,
  CONSTANT_Dynamic = 17,
  CONSTANT_InvokeDynamic = 18
};

// See Tables 4.1, 4.4, 4.5 in JVM Spec.
enum ACCESS {
  ACC_PUBLIC = 0x0001,
  ACC_PRIVATE = 0x0002,
  ACC_PROTECTED = 0x0004,
  ACC_STATIC = 0x0008,
  ACC_FINAL = 0x0010,
  ACC_SYNCHRONIZED = 0x0020,
  ACC_BRIDGE = 0x0040,
  ACC_VOLATILE = 0x0040,
  ACC_TRANSIENT = 0x0080,
  ACC_INTERFACE = 0x0200,
  ACC_ABSTRACT = 0x0400,
  ACC_SYNTHETIC = 0x1000
};

// See Table 4.7.20-A in Java 8 JVM Spec.
enum TARGET_TYPE {
  // Targets for type parameter declarations (ElementType.TYPE_PARAMETER):
  CLASS_TYPE_PARAMETER        = 0x00,
  METHOD_TYPE_PARAMETER       = 0x01,

  // Targets for type uses that may be externally visible in classes and members
  // (ElementType.TYPE_USE):
  CLASS_EXTENDS               = 0x10,
  CLASS_TYPE_PARAMETER_BOUND  = 0x11,
  METHOD_TYPE_PARAMETER_BOUND = 0x12,
  FIELD                       = 0x13,
  METHOD_RETURN               = 0x14,
  METHOD_RECEIVER             = 0x15,
  METHOD_FORMAL_PARAMETER     = 0x16,
  THROWS                      = 0x17,

  // TARGET_TYPE >= 0x40 is reserved for type uses that occur only within code
  // blocks. Ijar doesn't need to know about these.
};

struct Constant;

// TODO(adonovan) these globals are unfortunate
static std::vector<Constant *> const_pool_in;   // input constant pool
static std::vector<Constant *> const_pool_out;  // output constant_pool
static std::set<std::string> used_class_names;
static Constant *class_name;
static std::unordered_set<std::string> unknown_attributes;

// Returns the Constant object, given an index into the input constant pool.
// Note: constant(0) == NULL; this invariant is exploited by the
// InnerClassesAttribute, inter alia.
inline Constant *constant(int idx) {
  if (idx < 0 || (unsigned)idx >= const_pool_in.size()) {
    fprintf(stderr, "Illegal constant pool index: %d\n", idx);
    abort();
  }
  return const_pool_in[idx];
}

/**********************************************************************
 *                                                                    *
 *                             Constants                              *
 *                                                                    *
 **********************************************************************/

// See sec.4.4 of JVM spec.
struct Constant {

  Constant(u1 tag) :
      slot_(0),
      tag_(tag) {}

  virtual ~Constant() {}

  // For UTF-8 string constants, returns the encoded string.
  // Otherwise, returns an undefined string value suitable for debugging.
  virtual std::string Display() = 0;

  virtual void Write(u1 *&p) = 0;

  // Called by slot() when a constant has been identified as required
  // in the output classfile's constant pool.  This is a hook allowing
  // constants to register their dependency on other constants, by
  // calling slot() on them in turn.
  virtual void Keep() {}

  bool Kept() {
    return slot_ != 0;
  }

  // Returns the index of this constant in the output class's constant
  // pool, assigning a slot if not already done.
  u2 slot() {
    if (slot_ == 0) {
      Keep();
      slot_ = const_pool_out.size(); // BugBot's "narrowing" warning
                                     // is bogus.  The number of
                                     // output constants can't exceed
                                     // the number of input constants.
      if (slot_ == 0) {
        fprintf(stderr, "Constant::slot() called before output phase.\n");
        abort();
      }
      const_pool_out.push_back(this);
      if (tag_ == CONSTANT_Long || tag_ == CONSTANT_Double) {
        const_pool_out.push_back(NULL);
      }
    }
    return slot_;
  }

  u2 slot_; // zero => "this constant is unreachable garbage"
  u1 tag_;
};

// Extracts class names from a signature and puts them into the global
// variable used_class_names.
//
// desc: the descriptor class names should be extracted from.
// p: the position where the extraction should tart.
void ExtractClassNames(const std::string& desc, size_t* p);

// See sec.4.4.1 of JVM spec.
struct Constant_Class : Constant
{
  Constant_Class(u2 name_index) :
      Constant(CONSTANT_Class),
      name_index_(name_index) {}

  void Write(u1 *&p) {
    put_u1(p, tag_);
    put_u2be(p, constant(name_index_)->slot());
  }

  std::string Display() {
    return constant(name_index_)->Display();
  }

  void Keep() { constant(name_index_)->slot(); }

  u2 name_index_;
};

// See sec.4.4.2 of JVM spec.
struct Constant_FMIref : Constant
{
  Constant_FMIref(u1 tag,
                  u2 class_index,
                  u2 name_type_index) :
      Constant(tag),
      class_index_(class_index),
      name_type_index_(name_type_index) {}

  void Write(u1 *&p) {
    put_u1(p, tag_);
    put_u2be(p, constant(class_index_)->slot());
    put_u2be(p, constant(name_type_index_)->slot());
  }

  std::string Display() {
    return constant(class_index_)->Display() + "::" +
        constant(name_type_index_)->Display();
  }

  void Keep() {
    constant(class_index_)->slot();
    constant(name_type_index_)->slot();
  }

  u2 class_index_;
  u2 name_type_index_;
};

// See sec.4.4.3 of JVM spec.
struct Constant_String : Constant
{
  Constant_String(u2 string_index) :
      Constant(CONSTANT_String),
      string_index_(string_index) {}

  void Write(u1 *&p) {
    put_u1(p, tag_);
    put_u2be(p, constant(string_index_)->slot());
  }

  std::string Display() {
    return "\"" + constant(string_index_)->Display() + "\"";
  }

  void Keep() { constant(string_index_)->slot(); }

  u2 string_index_;
};

// See sec.4.4.4 of JVM spec.
struct Constant_IntegerOrFloat : Constant
{
  Constant_IntegerOrFloat(u1 tag, u4 bytes) :
      Constant(tag),
      bytes_(bytes) {}

  void Write(u1 *&p) {
    put_u1(p, tag_);
    put_u4be(p, bytes_);
  }

  std::string Display() { return "int/float"; }

  u4 bytes_;
};

// See sec.4.4.5 of JVM spec.
struct Constant_LongOrDouble : Constant_IntegerOrFloat
{
  Constant_LongOrDouble(u1 tag, u4 high_bytes, u4 low_bytes) :
      Constant_IntegerOrFloat(tag, high_bytes),
      low_bytes_(low_bytes) {}

  void Write(u1 *&p) {
    put_u1(p, tag_);
    put_u4be(p, bytes_);
    put_u4be(p, low_bytes_);
  }

  std::string Display() { return "long/double"; }

  u4 low_bytes_;
};

// See sec.4.4.6 of JVM spec.
struct Constant_NameAndType : Constant
{
  Constant_NameAndType(u2 name_index, u2 descr_index) :
      Constant(CONSTANT_NameAndType),
      name_index_(name_index),
      descr_index_(descr_index) {}

  void Write(u1 *&p) {
    put_u1(p, tag_);
    put_u2be(p, constant(name_index_)->slot());
    put_u2be(p, constant(descr_index_)->slot());
  }

  std::string Display() {
    return constant(name_index_)->Display() + "::" +
        constant(descr_index_)->Display();
  }

  void Keep() {
    constant(name_index_)->slot();
    constant(descr_index_)->slot();
  }

  u2 name_index_;
  u2 descr_index_;
};

// See sec.4.4.7 of JVM spec.
struct Constant_Utf8 : Constant
{
  Constant_Utf8(u4 length, const u1 *utf8) :
      Constant(CONSTANT_Utf8),
      length_(length),
      utf8_(utf8) {}

  void Write(u1 *&p) {
    put_u1(p, tag_);
    put_u2be(p, length_);
    put_n(p, utf8_, length_);
  }

  std::string Display() {
    return std::string((const char*) utf8_, length_);
  }

  u4 length_;
  const u1 *utf8_;
};

// See sec.4.4.8 of JVM spec.
struct Constant_MethodHandle : Constant
{
  Constant_MethodHandle(u1 reference_kind, u2 reference_index) :
      Constant(CONSTANT_MethodHandle),
      reference_kind_(reference_kind),
      reference_index_(reference_index) {}

  void Write(u1 *&p) {
    put_u1(p, tag_);
    put_u1(p, reference_kind_);
    put_u2be(p, reference_index_);
  }

  std::string Display() {
    return "Constant_MethodHandle::" + ToString(reference_kind_) + "::"
        + constant(reference_index_)->Display();
  }

  u1 reference_kind_;
  u2 reference_index_;
};

// See sec.4.4.9 of JVM spec.
struct Constant_MethodType : Constant
{
  Constant_MethodType(u2 descriptor_index) :
      Constant(CONSTANT_MethodType),
      descriptor_index_(descriptor_index) {}

  void Write(u1 *&p) {
    put_u1(p, tag_);
    put_u2be(p, descriptor_index_);
  }

  std::string Display() {
    return  "Constant_MethodType::" + constant(descriptor_index_)->Display();
  }

  u2 descriptor_index_;
};

// See sec.4.4.10 of JVM spec.
struct Constant_Dynamic : Constant {
  Constant_Dynamic(u2 bootstrap_method_attr_index, u2 name_and_type_index)
      : Constant(CONSTANT_Dynamic),
        bootstrap_method_attr_index_(bootstrap_method_attr_index),
        name_and_type_index_(name_and_type_index) {}

  void Write(u1 *&p) {
    put_u1(p, tag_);
    put_u2be(p, bootstrap_method_attr_index_);
    put_u2be(p, name_and_type_index_);
  }

  std::string Display() {
    return "Constant_Dynamic::" + ToString(bootstrap_method_attr_index_) +
           "::" + constant(name_and_type_index_)->Display();
  }

  u2 bootstrap_method_attr_index_;
  u2 name_and_type_index_;
};

struct Constant_InvokeDynamic : Constant {
  Constant_InvokeDynamic(u2 bootstrap_method_attr_index, u2 name_and_type_index)
      : Constant(CONSTANT_InvokeDynamic),
        bootstrap_method_attr_index_(bootstrap_method_attr_index),
        name_and_type_index_(name_and_type_index) {}

  void Write(u1 *&p) {
    put_u1(p, tag_);
    put_u2be(p, bootstrap_method_attr_index_);
    put_u2be(p, name_and_type_index_);
  }

  std::string Display() {
    return  "Constant_InvokeDynamic::"
        + ToString(bootstrap_method_attr_index_) + "::"
        + constant(name_and_type_index_)->Display();
  }

  u2 bootstrap_method_attr_index_;
  u2 name_and_type_index_;
};

/**********************************************************************
 *                                                                    *
 *                             Attributes                             *
 *                                                                    *
 **********************************************************************/

// See sec.4.7 of JVM spec.
struct Attribute {

  virtual ~Attribute() {}
  virtual void Write(u1 *&p) = 0;
  virtual void ExtractClassNames() {}
  virtual bool KeepForCompile() const { return false; }

  void WriteProlog(u1 *&p, u2 length) {
    put_u2be(p, attribute_name_->slot());
    put_u4be(p, length);
  }

  Constant *attribute_name_;
};

struct HasAttrs {
  std::vector<Attribute*> attributes;

  void WriteAttrs(u1 *&p);
  void ReadAttrs(const u1 *&p);

  virtual ~HasAttrs() {
    for (const auto *attribute : attributes) {
      delete attribute;
    }
  }

  void ExtractClassNames() {
    for (auto *attribute : attributes) {
      attribute->ExtractClassNames();
    }
  }
};

// See sec.4.7.5 of JVM spec.
struct ExceptionsAttribute : Attribute {

  static ExceptionsAttribute* Read(const u1 *&p, Constant *attribute_name) {
    ExceptionsAttribute *attr = new ExceptionsAttribute;
    attr->attribute_name_ = attribute_name;
    u2 number_of_exceptions = get_u2be(p);
    for (int ii = 0; ii < number_of_exceptions; ++ii) {
      attr->exceptions_.push_back(constant(get_u2be(p)));
    }
    return attr;
  }

  void Write(u1 *&p) {
    WriteProlog(p, exceptions_.size() * 2 + 2);
    put_u2be(p, exceptions_.size());
    for (size_t ii = 0; ii < exceptions_.size(); ++ii) {
      put_u2be(p, exceptions_[ii]->slot());
    }
  }

  std::vector<Constant*> exceptions_;
};

// See sec.4.7.6 of JVM spec.
struct InnerClassesAttribute : Attribute {

  struct Entry {
    Constant *inner_class_info;
    Constant *outer_class_info;
    Constant *inner_name;
    u2 inner_class_access_flags;
  };

  virtual ~InnerClassesAttribute() {
    for (size_t i = 0; i < entries_.size(); i++) {
      delete entries_[i];
    }
  }

  static InnerClassesAttribute* Read(const u1 *&p, Constant *attribute_name) {
    InnerClassesAttribute *attr = new InnerClassesAttribute;
    attr->attribute_name_ = attribute_name;

    u2 number_of_classes = get_u2be(p);
    for (int ii = 0; ii < number_of_classes; ++ii) {
      Entry *entry = new Entry;
      entry->inner_class_info = constant(get_u2be(p));
      entry->outer_class_info = constant(get_u2be(p));
      entry->inner_name = constant(get_u2be(p));
      entry->inner_class_access_flags = get_u2be(p);

      attr->entries_.push_back(entry);
    }
    return attr;
  }

  void Write(u1 *&p) {
    std::set<int> kept_entries;
    // We keep an entry if the constant referring to the inner class is already
    // kept. Then we mark its outer class and its class name as kept, too, then
    // iterate until a fixed point is reached.
    int entry_count;

    do {
      entry_count = kept_entries.size();
      for (int i_entry = 0; i_entry < static_cast<int>(entries_.size());
           ++i_entry) {
        Entry* entry = entries_[i_entry];
        if (entry->inner_class_info->Kept() ||
            used_class_names.find(entry->inner_class_info->Display()) !=
                used_class_names.end() ||
            entry->outer_class_info == class_name) {
          if (entry->inner_name == NULL) {
            // JVMS 4.7.6: inner_name_index is zero iff the class is anonymous
            continue;
          }

          kept_entries.insert(i_entry);

          // JVMS 4.7.6: outer_class_info_index is zero for top-level classes
          if (entry->outer_class_info != NULL) {
            entry->outer_class_info->slot();
          }

          entry->inner_name->slot();
        }
      }
    } while (entry_count != static_cast<int>(kept_entries.size()));

    if (kept_entries.empty()) {
      return;
    }

    WriteProlog(p, 2 + kept_entries.size() * 8);
    put_u2be(p, kept_entries.size());

    for (std::set<int>::iterator it = kept_entries.begin();
         it != kept_entries.end();
         ++it) {
      Entry *entry = entries_[*it];
      put_u2be(p, entry->inner_class_info == NULL
               ? 0
               : entry->inner_class_info->slot());
      put_u2be(p, entry->outer_class_info == NULL
               ? 0
               : entry->outer_class_info->slot());
      put_u2be(p, entry->inner_name == NULL
               ? 0
               : entry->inner_name->slot());
      put_u2be(p, entry->inner_class_access_flags);
    }
  }

  std::vector<Entry*> entries_;
};

// See sec.4.7.7 of JVM spec.
// We preserve EnclosingMethod attributes to be able to identify local and
// anonymous classes. These classes will be stripped of most content, as they
// represent implementation details that shoudn't leak into the ijars. Omitting
// EnclosingMethod attributes can lead to type-checking failures in the presence
// of generics (see b/9070939).
struct EnclosingMethodAttribute : Attribute {

  static EnclosingMethodAttribute* Read(const u1 *&p,
                                        Constant *attribute_name) {
    EnclosingMethodAttribute *attr = new EnclosingMethodAttribute;
    attr->attribute_name_ = attribute_name;
    attr->class_ = constant(get_u2be(p));
    attr->method_ = constant(get_u2be(p));
    return attr;
  }

  void Write(u1 *&p) {
    WriteProlog(p, 4);
    put_u2be(p, class_->slot());
    put_u2be(p, method_ == NULL ? 0 : method_->slot());
  }

  Constant *class_;
  Constant *method_;
};

// See sec.4.7.16.1 of JVM spec.
// Used by AnnotationDefault and other attributes.
struct ElementValue {
  virtual ~ElementValue() {}
  virtual void Write(u1 *&p) = 0;
  virtual void ExtractClassNames() {}
  static ElementValue* Read(const u1 *&p);
  u1 tag_;
  u4 length_;
};

struct BaseTypeElementValue : ElementValue {
  void Write(u1 *&p) {
    put_u1(p, tag_);
    put_u2be(p, const_value_->slot());
  }
  static BaseTypeElementValue *Read(const u1 *&p) {
    BaseTypeElementValue *value = new BaseTypeElementValue;
    value->const_value_ = constant(get_u2be(p));
    return value;
  }
  Constant *const_value_;
};

struct EnumTypeElementValue : ElementValue {
  void Write(u1 *&p) {
    put_u1(p, tag_);
    put_u2be(p, type_name_->slot());
    put_u2be(p, const_name_->slot());
  }
  static EnumTypeElementValue *Read(const u1 *&p) {
    EnumTypeElementValue *value = new EnumTypeElementValue;
    value->type_name_ = constant(get_u2be(p));
    value->const_name_ = constant(get_u2be(p));
    return value;
  }
  Constant *type_name_;
  Constant *const_name_;
};

struct ClassTypeElementValue : ElementValue {
  void Write(u1 *&p) {
    put_u1(p, tag_);
    put_u2be(p, class_info_->slot());
  }

  virtual void ExtractClassNames() {
    size_t idx = 0;
    devtools_ijar::ExtractClassNames(class_info_->Display(), &idx);
  }

  static ClassTypeElementValue *Read(const u1 *&p) {
    ClassTypeElementValue *value = new ClassTypeElementValue;
    value->class_info_ = constant(get_u2be(p));
    return value;
  }
  Constant *class_info_;
};

struct ArrayTypeElementValue : ElementValue {
  virtual ~ArrayTypeElementValue() {
    for (const auto *value : values_) {
      delete value;
    }
  }

  virtual void ExtractClassNames() {
    for (auto *value : values_) {
      value->ExtractClassNames();
    }
  }

  void Write(u1 *&p) {
    put_u1(p, tag_);
    put_u2be(p, values_.size());
    for (auto *value : values_) {
      value->Write(p);
    }
  }
  static ArrayTypeElementValue *Read(const u1 *&p) {
    ArrayTypeElementValue *value = new ArrayTypeElementValue;
    u2 num_values = get_u2be(p);
    for (int ii = 0; ii < num_values; ++ii) {
      value->values_.push_back(ElementValue::Read(p));
    }
    return value;
  }
  std::vector<ElementValue*> values_;
};

// See sec.4.7.16 of JVM spec.
struct Annotation {
  virtual ~Annotation() {
    for (size_t i = 0; i < element_value_pairs_.size(); i++) {
      delete element_value_pairs_[i]->element_value_;
      delete element_value_pairs_[i];
    }
  }

  void ExtractClassNames() {
    for (size_t i = 0; i < element_value_pairs_.size(); i++) {
      element_value_pairs_[i]->element_value_->ExtractClassNames();
    }
  }

  void Write(u1 *&p) {
    put_u2be(p, type_->slot());
    put_u2be(p, element_value_pairs_.size());
    for (size_t ii = 0; ii < element_value_pairs_.size(); ++ii) {
      put_u2be(p, element_value_pairs_[ii]->element_name_->slot());
      element_value_pairs_[ii]->element_value_->Write(p);
    }
  }
  static Annotation *Read(const u1 *&p) {
    Annotation *value = new Annotation;
    value->type_ = constant(get_u2be(p));
    u2 num_element_value_pairs = get_u2be(p);
    for (int ii = 0; ii < num_element_value_pairs; ++ii) {
      ElementValuePair *pair = new ElementValuePair;
      pair->element_name_ = constant(get_u2be(p));
      pair->element_value_ = ElementValue::Read(p);
      value->element_value_pairs_.push_back(pair);
    }
    return value;
  }
  Constant *type_;
  struct ElementValuePair {
    Constant *element_name_;
    ElementValue *element_value_;
  };
  std::vector<ElementValuePair*> element_value_pairs_;
};

// See sec 4.7.20 of Java 8 JVM Spec
//
// Each entry in the annotations table represents a single run-time visible
// annotation on a type used in a declaration or expression. The type_annotation
// structure has the following format:
//
// type_annotation {
//   u1 target_type;
//   union {
//     type_parameter_target;
//     supertype_target;
//     type_parameter_bound_target;
//     empty_target;
//     method_formal_parameter_target;
//     throws_target;
//     localvar_target;
//     catch_target;
//     offset_target;
//     type_argument_target;
//   } target_info;
//   type_path target_path;
//   u2        type_index;
//   u2        num_element_value_pairs;
//   {
//     u2            element_name_index;
//     element_value value;
//   }
//   element_value_pairs[num_element_value_pairs];
// }
//
struct TypeAnnotation {
  virtual ~TypeAnnotation() {
    delete target_info_;
    delete type_path_;
    delete annotation_;
  }

  void ExtractClassNames() {
    annotation_->ExtractClassNames();
  }

  void Write(u1 *&p) {
    put_u1(p, target_type_);
    target_info_->Write(p);
    type_path_->Write(p);
    annotation_->Write(p);
  }

  static TypeAnnotation *Read(const u1 *&p) {
    TypeAnnotation *value = new TypeAnnotation;
    value->target_type_ = get_u1(p);
    value->target_info_ = ReadTargetInfo(p, value->target_type_);
    value->type_path_ = TypePath::Read(p);
    value->annotation_ = Annotation::Read(p);
    return value;
  }

  struct TargetInfo {
    virtual ~TargetInfo() {}
    virtual void Write(u1 *&p) = 0;
  };

  struct TypeParameterTargetInfo : TargetInfo {
    void Write(u1 *&p) {
      put_u1(p, type_parameter_index_);
    }
    static TypeParameterTargetInfo *Read(const u1 *&p) {
      TypeParameterTargetInfo *value = new TypeParameterTargetInfo;
      value->type_parameter_index_ = get_u1(p);
      return value;
    }
    u1 type_parameter_index_;
  };

  struct ClassExtendsInfo : TargetInfo {
    void Write(u1 *&p) {
      put_u2be(p, supertype_index_);
    }
    static ClassExtendsInfo *Read(const u1 *&p) {
      ClassExtendsInfo *value = new ClassExtendsInfo;
      value->supertype_index_ = get_u2be(p);
      return value;
    }
    u2 supertype_index_;
  };

  struct TypeParameterBoundInfo : TargetInfo {
    void Write(u1 *&p) {
      put_u1(p, type_parameter_index_);
      put_u1(p, bound_index_);
    }
    static TypeParameterBoundInfo *Read(const u1 *&p) {
      TypeParameterBoundInfo *value = new TypeParameterBoundInfo;
      value->type_parameter_index_ = get_u1(p);
      value->bound_index_ = get_u1(p);
      return value;
    }
    u1 type_parameter_index_;
    u1 bound_index_;
  };

  struct EmptyInfo : TargetInfo {
    void Write(u1 *& /*p*/) {}
    static EmptyInfo *Read(const u1 *& /*p*/) { return new EmptyInfo; }
  };

  struct MethodFormalParameterInfo : TargetInfo {
    void Write(u1 *&p) {
      put_u1(p, method_formal_parameter_index_);
    }
    static MethodFormalParameterInfo *Read(const u1 *&p) {
      MethodFormalParameterInfo *value = new MethodFormalParameterInfo;
      value->method_formal_parameter_index_ = get_u1(p);
      return value;
    }
    u1 method_formal_parameter_index_;
  };

  struct ThrowsTypeInfo : TargetInfo {
    void Write(u1 *&p) {
      put_u2be(p, throws_type_index_);
    }
    static ThrowsTypeInfo *Read(const u1 *&p) {
      ThrowsTypeInfo *value = new ThrowsTypeInfo;
      value->throws_type_index_ = get_u2be(p);
      return value;
    }
    u2 throws_type_index_;
  };

  static TargetInfo *ReadTargetInfo(const u1 *&p, u1 target_type) {
    switch (target_type) {
      case CLASS_TYPE_PARAMETER:
      case METHOD_TYPE_PARAMETER:
        return TypeParameterTargetInfo::Read(p);
      case CLASS_EXTENDS:
        return ClassExtendsInfo::Read(p);
      case CLASS_TYPE_PARAMETER_BOUND:
      case METHOD_TYPE_PARAMETER_BOUND:
        return TypeParameterBoundInfo::Read(p);
      case FIELD:
      case METHOD_RETURN:
      case METHOD_RECEIVER:
        return new EmptyInfo;
      case METHOD_FORMAL_PARAMETER:
        return MethodFormalParameterInfo::Read(p);
      case THROWS:
        return ThrowsTypeInfo::Read(p);
      default:
        fprintf(stderr, "Illegal type annotation target type: %d\n",
                target_type);
        abort();
    }
  }

  struct TypePath {
    void Write(u1 *&p) {
      put_u1(p, path_.size());
      for (TypePathEntry entry : path_) {
        put_u1(p, entry.type_path_kind_);
        put_u1(p, entry.type_argument_index_);
      }
    }
    static TypePath *Read(const u1 *&p) {
      TypePath *value = new TypePath;
      u1 path_length = get_u1(p);
      for (int ii = 0; ii < path_length; ++ii) {
        TypePathEntry entry;
        entry.type_path_kind_ = get_u1(p);
        entry.type_argument_index_ = get_u1(p);
        value->path_.push_back(entry);
      }
      return value;
    }

    struct TypePathEntry {
      u1 type_path_kind_;
      u1 type_argument_index_;
    };
    std::vector<TypePathEntry> path_;
  };

  u1 target_type_;
  TargetInfo *target_info_;
  TypePath *type_path_;
  Annotation *annotation_;
};

struct AnnotationTypeElementValue : ElementValue {
  virtual ~AnnotationTypeElementValue() {
    delete annotation_;
  }

  void Write(u1 *&p) {
    put_u1(p, tag_);
    annotation_->Write(p);
  }
  static AnnotationTypeElementValue *Read(const u1 *&p) {
    AnnotationTypeElementValue *value = new AnnotationTypeElementValue;
    value->annotation_ = Annotation::Read(p);
    return value;
  }

  Annotation *annotation_;
};

ElementValue* ElementValue::Read(const u1 *&p) {
  const u1* start = p;
  ElementValue *result;
  u1 tag = get_u1(p);
  if (tag != 0 && strchr("BCDFIJSZs", (char) tag) != NULL) {
    result = BaseTypeElementValue::Read(p);
  } else if ((char) tag == 'e') {
    result = EnumTypeElementValue::Read(p);
  } else if ((char) tag == 'c') {
    result = ClassTypeElementValue::Read(p);
  } else if ((char) tag == '[') {
    result = ArrayTypeElementValue::Read(p);
  } else if ((char) tag == '@') {
    result = AnnotationTypeElementValue::Read(p);
  } else {
    fprintf(stderr, "Illegal element_value::tag: %d\n", tag);
    abort();
  }
  result->tag_ = tag;
  result->length_ = p - start;
  return result;
}

// See sec.4.7.20 of JVM spec.
// We preserve AnnotationDefault attributes because they are required
// in order to make use of an annotation in new code.
struct AnnotationDefaultAttribute : Attribute {
  virtual ~AnnotationDefaultAttribute() {
    delete default_value_;
  }

  static AnnotationDefaultAttribute* Read(const u1 *&p,
                                          Constant *attribute_name) {
    AnnotationDefaultAttribute *attr = new AnnotationDefaultAttribute;
    attr->attribute_name_ = attribute_name;
    attr->default_value_ = ElementValue::Read(p);
    return attr;
  }

  void Write(u1 *&p) {
    WriteProlog(p, default_value_->length_);
    default_value_->Write(p);
  }

  virtual void ExtractClassNames() {
    default_value_->ExtractClassNames();
  }

  ElementValue *default_value_;
};

// See sec.4.7.2 of JVM spec.
// We preserve ConstantValue attributes because they are required for
// compile-time constant propagation.
struct ConstantValueAttribute : Attribute {

  static ConstantValueAttribute* Read(const u1 *&p, Constant *attribute_name) {
    ConstantValueAttribute *attr = new ConstantValueAttribute;
    attr->attribute_name_ = attribute_name;
    attr->constantvalue_ = constant(get_u2be(p));
    return attr;
  }

  void Write(u1 *&p) {
    WriteProlog(p, 2);
    put_u2be(p, constantvalue_->slot());
  }

  Constant *constantvalue_;
};

// See sec.4.7.9 of JVM spec.
// We preserve Signature attributes because they are required by the
// compiler for type-checking of generics.
struct SignatureAttribute : Attribute {

  static SignatureAttribute* Read(const u1 *&p, Constant *attribute_name) {
    SignatureAttribute *attr = new SignatureAttribute;
    attr->attribute_name_ = attribute_name;
    attr->signature_  = constant(get_u2be(p));
    return attr;
  }

  void Write(u1 *&p) {
    WriteProlog(p, 2);
    put_u2be(p, signature_->slot());
  }

  virtual void ExtractClassNames() {
    size_t signature_idx = 0;
    devtools_ijar::ExtractClassNames(signature_->Display(), &signature_idx);
  }

  Constant *signature_;
};

// See sec.4.7.15 of JVM spec.
// We preserve Deprecated attributes because they are required by the
// compiler to generate warning messages.
struct DeprecatedAttribute : Attribute {
  static DeprecatedAttribute *Read(const u1 *& /*p*/,
                                   Constant *attribute_name) {
    DeprecatedAttribute *attr = new DeprecatedAttribute;
    attr->attribute_name_ = attribute_name;
    return attr;
  }

  void Write(u1 *&p) {
    WriteProlog(p, 0);
  }
};

// See sec.4.7.16-17 of JVM spec v3.  Includes RuntimeVisible and
// RuntimeInvisible.
//
// We preserve all annotations.
struct AnnotationsAttribute : Attribute {
  virtual ~AnnotationsAttribute() {
    for (size_t i = 0; i < annotations_.size(); i++) {
      delete annotations_[i];
    }
  }

  static AnnotationsAttribute* Read(const u1 *&p, Constant *attribute_name) {
    AnnotationsAttribute *attr = new AnnotationsAttribute;
    attr->attribute_name_ = attribute_name;
    u2 num_annotations = get_u2be(p);
    for (int ii = 0; ii < num_annotations; ++ii) {
      Annotation *annotation = Annotation::Read(p);
      attr->annotations_.push_back(annotation);
    }
    return attr;
  }

  virtual void ExtractClassNames() {
    for (auto *annotation : annotations_) {
      annotation->ExtractClassNames();
    }
  }

  virtual bool KeepForCompile() const {
    for (auto *annotation : annotations_) {
      if (annotation->type_->Display() == "Lkotlin/Metadata;") {
        return true;
      }
    }
    return false;
  }

  void Write(u1 *&p) {
    WriteProlog(p, -1);
    u1 *payload_start = p - 4;
    put_u2be(p, annotations_.size());
    for (auto *annotation : annotations_) {
      annotation->Write(p);
    }
    put_u4be(payload_start, p - 4 - payload_start);  // backpatch length
  }

  std::vector<Annotation*> annotations_;
};

// See sec.4.7.18-19 of JVM spec.  Includes RuntimeVisible and
// RuntimeInvisible.
//
// We preserve all annotations.
struct ParameterAnnotationsAttribute : Attribute {

  static ParameterAnnotationsAttribute* Read(const u1 *&p,
                                             Constant *attribute_name) {
    ParameterAnnotationsAttribute *attr = new ParameterAnnotationsAttribute;
    attr->attribute_name_ = attribute_name;
    u1 num_parameters = get_u1(p);
    for (int ii = 0; ii < num_parameters; ++ii) {
      std::vector<Annotation*> annotations;
      u2 num_annotations = get_u2be(p);
      for (int ii = 0; ii < num_annotations; ++ii) {
        Annotation *annotation = Annotation::Read(p);
        annotations.push_back(annotation);
      }
      attr->parameter_annotations_.push_back(annotations);
    }
    return attr;
  }

  virtual void ExtractClassNames() {
    for (size_t i = 0; i < parameter_annotations_.size(); i++) {
      const std::vector<Annotation*>& annotations = parameter_annotations_[i];
      for (size_t j = 0; j < annotations.size(); j++) {
        annotations[j]->ExtractClassNames();
      }
    }
  }

  void Write(u1 *&p) {
    WriteProlog(p, -1);
    u1 *payload_start = p - 4;
    put_u1(p, parameter_annotations_.size());
    for (size_t ii = 0; ii < parameter_annotations_.size(); ++ii) {
      std::vector<Annotation *> &annotations = parameter_annotations_[ii];
      put_u2be(p, annotations.size());
      for (size_t jj = 0; jj < annotations.size(); ++jj) {
        annotations[jj]->Write(p);
      }
    }
    put_u4be(payload_start, p - 4 - payload_start);  // backpatch length
  }

  std::vector<std::vector<Annotation*> > parameter_annotations_;
};

// See sec.4.7.20 of Java 8 JVM spec. Includes RuntimeVisibleTypeAnnotations
// and RuntimeInvisibleTypeAnnotations.
struct TypeAnnotationsAttribute : Attribute {
  static TypeAnnotationsAttribute *Read(const u1 *&p, Constant *attribute_name,
                                        u4 /*attribute_length*/) {
    auto attr = new TypeAnnotationsAttribute;
    attr->attribute_name_ = attribute_name;
    u2 num_annotations = get_u2be(p);
    for (int ii = 0; ii < num_annotations; ++ii) {
      TypeAnnotation *annotation = TypeAnnotation::Read(p);
      attr->type_annotations_.push_back(annotation);
    }
    return attr;
  }

  virtual void ExtractClassNames() {
    for (auto *type_annotation : type_annotations_) {
      type_annotation->ExtractClassNames();
    }
  }

  void Write(u1 *&p) {
    WriteProlog(p, -1);
    u1 *payload_start = p - 4;
    put_u2be(p, type_annotations_.size());
    for (TypeAnnotation *annotation : type_annotations_) {
      annotation->Write(p);
    }
    put_u4be(payload_start, p - 4 - payload_start);  // backpatch length
  }

  std::vector<TypeAnnotation*> type_annotations_;
};

// See JVMS §4.7.24
struct MethodParametersAttribute : Attribute {
  static MethodParametersAttribute *Read(const u1 *&p, Constant *attribute_name,
                                         u4 /*attribute_length*/) {
    auto attr = new MethodParametersAttribute;
    attr->attribute_name_ = attribute_name;
    u1 parameters_count = get_u1(p);
    for (int ii = 0; ii < parameters_count; ++ii) {
      MethodParameter* parameter = new MethodParameter;
      int name_id = get_u2be(p);
      parameter->name_ = name_id == 0 ? NULL : constant(name_id);
      parameter->access_flags_ = get_u2be(p);
      attr->parameters_.push_back(parameter);
    }
    return attr;
  }

  void Write(u1 *&p) {
    WriteProlog(p, -1);
    u1 *payload_start = p - 4;
    put_u1(p, parameters_.size());
    for (MethodParameter* parameter : parameters_) {
      put_u2be(p, parameter->name_ == NULL ? 0 : parameter->name_->slot());
      put_u2be(p, parameter->access_flags_);
    }
    put_u4be(payload_start, p - 4 - payload_start);  // backpatch length
  }

  struct MethodParameter {
    Constant *name_;
    u2 access_flags_;
  };

  std::vector<MethodParameter*> parameters_;
};

// See JVMS §4.7.28
struct NestHostAttribute : Attribute {
  static NestHostAttribute *Read(const u1 *&p, Constant *attribute_name,
                                 u4 /*attribute_length*/) {
    auto attr = new NestHostAttribute;
    attr->attribute_name_ = attribute_name;
    attr->host_class_index_ = constant(get_u2be(p));
    return attr;
  }

  void Write(u1 *&p) {
    WriteProlog(p, 2);
    put_u2be(p, host_class_index_->slot());
  }

  Constant *host_class_index_;
};

// See JVMS §4.7.29
struct NestMembersAttribute : Attribute {
  static NestMembersAttribute *Read(const u1 *&p, Constant *attribute_name,
                                    u4 /*attribute_length*/) {
    auto attr = new NestMembersAttribute;
    attr->attribute_name_ = attribute_name;
    u2 number_of_classes = get_u2be(p);
    for (int ii = 0; ii < number_of_classes; ++ii) {
      attr->classes_.push_back(constant(get_u2be(p)));
    }
    return attr;
  }

  void Write(u1 *&p) {
    std::set<int> kept_entries;
    for (size_t ii = 0; ii < classes_.size(); ++ii) {
      Constant *class_ = classes_[ii];
      if (class_->Kept() || (used_class_names.find(class_->Display()) !=
                             used_class_names.end())) {
        kept_entries.insert(ii);
      }
    }
    if (kept_entries.empty()) {
      return;
    }
    WriteProlog(p, kept_entries.size() * 2 + 2);
    put_u2be(p, kept_entries.size());
    for (std::set<int>::iterator it = kept_entries.begin();
         it != kept_entries.end(); ++it) {
      put_u2be(p, classes_[*it]->slot());
    }
  }

  std::vector<Constant *> classes_;
};

// See JVMS §4.7.30
struct RecordAttribute : Attribute {
  static RecordAttribute *Read(const u1 *&p, Constant *attribute_name,
                                    u4 attribute_length) {
    auto attr = new RecordAttribute;
    attr->attribute_name_ = attribute_name;
    attr->attribute_length_ = attribute_length;
    u2 components_length = get_u2be(p);
    for (int i = 0; i < components_length; ++i) {
      attr->components_.push_back(RecordComponentInfo::Read(p));
    }
    return attr;
  }

  void Write(u1 *&p) {
    u1 *tmp = new u1[attribute_length_];
    u1 *start = tmp;
    put_u2be(tmp, components_.size());
    for (size_t i = 0; i < components_.size(); ++i) {
      components_[i]->Write(tmp);
    }
    u2 length = tmp - start;
    WriteProlog(p, length);
    memcpy(p, start, length);
    p += length;
  }

  struct RecordComponentInfo : HasAttrs {
    void Write(u1 *&p) {
      put_u2be(p, name_->slot());
      put_u2be(p, descriptor_->slot());
      WriteAttrs(p);
    }
    static RecordComponentInfo *Read(const u1 *&p) {
      RecordComponentInfo *value = new RecordComponentInfo;
      value->name_ = constant(get_u2be(p));
      value->descriptor_ = constant(get_u2be(p));
      value->ReadAttrs(p);
      return value;
    }

    Constant *name_;
    Constant *descriptor_;
  };

  u4 attribute_length_;
  std::vector<RecordComponentInfo *> components_;
};

// See JVMS §4.7.31
struct PermittedSubclassesAttribute : Attribute {
  static PermittedSubclassesAttribute *Read(const u1 *&p,
                                            Constant *attribute_name) {
    PermittedSubclassesAttribute *attr = new PermittedSubclassesAttribute;
    attr->attribute_name_ = attribute_name;
    u2 number_of_exceptions = get_u2be(p);
    for (int ii = 0; ii < number_of_exceptions; ++ii) {
      attr->permitted_subclasses_.push_back(constant(get_u2be(p)));
    }
    return attr;
  }

  void Write(u1 *&p) {
    WriteProlog(p, permitted_subclasses_.size() * 2 + 2);
    put_u2be(p, permitted_subclasses_.size());
    for (size_t ii = 0; ii < permitted_subclasses_.size(); ++ii) {
      put_u2be(p, permitted_subclasses_[ii]->slot());
    }
  }

  std::vector<Constant *> permitted_subclasses_;
};

struct GeneralAttribute : Attribute {
  static GeneralAttribute* Read(const u1 *&p, Constant *attribute_name,
                                u4 attribute_length) {
    auto attr = new GeneralAttribute;
    attr->attribute_name_ = attribute_name;
    attr->attribute_length_ = attribute_length;
    attr->attribute_content_ = p;
    p += attribute_length;
    return attr;
  }

  void Write(u1 *&p) {
    WriteProlog(p, attribute_length_);
    put_n(p, attribute_content_, attribute_length_);
  }

  u4 attribute_length_;
  const u1 *attribute_content_;
};

/**********************************************************************
 *                                                                    *
 *                             ClassFile                              *
 *                                                                    *
 **********************************************************************/

// A field or method.
// See sec.4.5 and 4.6 of JVM spec.
struct Member : HasAttrs {
  u2 access_flags;
  Constant *name;
  Constant *descriptor;

  static Member* Read(const u1 *&p) {
    Member *m = new Member;
    m->access_flags = get_u2be(p);
    m->name = constant(get_u2be(p));
    m->descriptor = constant(get_u2be(p));
    m->ReadAttrs(p);
    return m;
  }

  void Write(u1 *&p) {
    put_u2be(p, access_flags);
    put_u2be(p, name->slot());
    put_u2be(p, descriptor->slot());
    WriteAttrs(p);
  }
};

// See sec.4.1 of JVM spec.
struct ClassFile : HasAttrs {

  size_t length;

  // Header:
  u4 magic;
  u2 major;
  u2 minor;

  // Body:
  u2 access_flags;
  Constant *this_class;
  Constant *super_class;
  std::vector<Constant*> interfaces;
  std::vector<Member*> fields;
  std::vector<Member*> methods;

  virtual ~ClassFile() {
    for (size_t i = 0; i < fields.size(); i++) {
      delete fields[i];
    }

    for (size_t i = 0; i < methods.size(); i++) {
      delete methods[i];
    }

    // Constants do not need to be deleted; they are owned by the constant pool.
  }

  void WriteClass(u1 *&p);

  bool ReadConstantPool(const u1 *&p);

  bool KeepForCompile();

  bool IsLocalOrAnonymous();

  void WriteHeader(u1 *&p) {
    put_u4be(p, magic);
    put_u2be(p, major);
    put_u2be(p, minor);

    put_u2be(p, const_pool_out.size());
    for (u2 ii = 1; ii < const_pool_out.size(); ++ii) {
      if (const_pool_out[ii] != NULL) { // NB: NULLs appear after long/double.
        const_pool_out[ii]->Write(p);
      }
    }
  }

  void WriteBody(u1 *&p) {
    put_u2be(p, access_flags);
    put_u2be(p, this_class->slot());
    put_u2be(p, super_class == NULL ? 0 : super_class->slot());
    put_u2be(p, interfaces.size());
    for (size_t ii = 0; ii < interfaces.size(); ++ii) {
      put_u2be(p, interfaces[ii]->slot());
    }
    put_u2be(p, fields.size());
    for (size_t ii = 0; ii < fields.size(); ++ii) {
      fields[ii]->Write(p);
    }
    put_u2be(p, methods.size());
    for (size_t ii = 0; ii < methods.size(); ++ii) {
      methods[ii]->Write(p);
    }

    Attribute* inner_classes = NULL;

    // Make the inner classes attribute the last, so that it can know which
    // constants were needed
    for (size_t ii = 0; ii < attributes.size(); ii++) {
      if (attributes[ii]->attribute_name_->Display() == "InnerClasses") {
        inner_classes = attributes[ii];
        attributes.erase(attributes.begin() + ii);
        break;
      }
    }

    if (inner_classes != NULL) {
      attributes.push_back(inner_classes);
    }

    Attribute* nest_members = NULL;

    for (size_t ii = 0; ii < attributes.size(); ii++) {
      if (attributes[ii]->attribute_name_->Display() == "NestMembers") {
        nest_members = attributes[ii];
        attributes.erase(attributes.begin() + ii);
        break;
      }
    }

    if (nest_members != NULL) {
      attributes.push_back(nest_members);
    }

    WriteAttrs(p);
  }

};

void HasAttrs::ReadAttrs(const u1 *&p) {
  u2 attributes_count = get_u2be(p);
  for (int ii = 0; ii < attributes_count; ii++) {
    Constant *attribute_name = constant(get_u2be(p));
    u4 attribute_length = get_u4be(p);

    std::string attr_name = attribute_name->Display();
    if (attr_name == "SourceFile" ||
        attr_name == "StackMapTable" ||
        attr_name == "LineNumberTable" ||
        attr_name == "LocalVariableTable" ||
        attr_name == "LocalVariableTypeTable" ||
        attr_name == "Code" ||
        attr_name == "Synthetic" ||
        attr_name == "BootstrapMethods" ||
        attr_name == "SourceDebugExtension") {
      p += attribute_length; // drop these attributes
    } else if (attr_name == "Exceptions") {
      attributes.push_back(ExceptionsAttribute::Read(p, attribute_name));
    } else if (attr_name == "Signature") {
      attributes.push_back(SignatureAttribute::Read(p, attribute_name));
    } else if (attr_name == "Deprecated") {
      attributes.push_back(DeprecatedAttribute::Read(p, attribute_name));
    } else if (attr_name == "EnclosingMethod") {
      attributes.push_back(EnclosingMethodAttribute::Read(p, attribute_name));
    } else if (attr_name == "InnerClasses") {
      // TODO(bazel-team): omit private inner classes
      attributes.push_back(InnerClassesAttribute::Read(p, attribute_name));
    } else if (attr_name == "AnnotationDefault") {
      attributes.push_back(AnnotationDefaultAttribute::Read(p, attribute_name));
    } else if (attr_name == "ConstantValue") {
      attributes.push_back(ConstantValueAttribute::Read(p, attribute_name));
    } else if (attr_name == "RuntimeVisibleAnnotations" ||
               attr_name == "RuntimeInvisibleAnnotations") {
      attributes.push_back(AnnotationsAttribute::Read(p, attribute_name));
    } else if (attr_name == "RuntimeVisibleParameterAnnotations" ||
               attr_name == "RuntimeInvisibleParameterAnnotations") {
      attributes.push_back(
          ParameterAnnotationsAttribute::Read(p, attribute_name));
    } else if (attr_name == "Scala" || attr_name == "ScalaSig" ||
               attr_name == "ScalaInlineInfo" || attr_name == "TASTY" ||
               attr_name == "TurbineTransitiveJar") {
      // These are opaque blobs, so can be handled with a general
      // attribute handler
      attributes.push_back(GeneralAttribute::Read(p, attribute_name,
                                                  attribute_length));
    } else if (attr_name == "RuntimeVisibleTypeAnnotations" ||
               attr_name == "RuntimeInvisibleTypeAnnotations") {
      attributes.push_back(TypeAnnotationsAttribute::Read(p, attribute_name,
                                                          attribute_length));
    } else if (attr_name == "MethodParameters") {
      attributes.push_back(
          MethodParametersAttribute::Read(p, attribute_name, attribute_length));
    } else if (attr_name == "NestHost") {
      attributes.push_back(
          NestHostAttribute::Read(p, attribute_name, attribute_length));
    } else if (attr_name == "NestMembers") {
      attributes.push_back(
          NestMembersAttribute::Read(p, attribute_name, attribute_length));
    } else if (attr_name == "Record") {
      attributes.push_back(
          RecordAttribute::Read(p, attribute_name, attribute_length));
    } else if (attr_name == "PermittedSubclasses") {
      attributes.push_back(
          PermittedSubclassesAttribute::Read(p, attribute_name));
    } else {
      // Skip over unknown attributes with a warning.  The JVM spec
      // says this is ok, so long as we handle the mandatory attributes.
      // Don't even warn for the D8 desugar SynthesizedClass attribute. It is
      // not relevant for ijar.
      if (attr_name != "com.android.tools.r8.SynthesizedClass" &&
          attr_name != "com.android.tools.r8.SynthesizedClassV2") {
        // Only warn about the first occurrence of each unknown attribute.
        if (unknown_attributes.insert(attr_name).second) {
          fprintf(stderr, "ijar: skipping unknown attribute: \"%s\".\n",
                  attr_name.c_str());
        }
      }
      p += attribute_length;
    }
  }
}

void HasAttrs::WriteAttrs(u1 *&p) {
  u1* p_size = p;

  put_u2be(p, 0);
  int n_written_attrs = 0;
  for (size_t ii = 0; ii < attributes.size(); ii++) {
    u1* before = p;
    attributes[ii]->Write(p);
    if (p != before) {
      n_written_attrs++;
    }
  }

  put_u2be(p_size, n_written_attrs);
}

// See sec.4.4 of JVM spec.
bool ClassFile::ReadConstantPool(const u1 *&p) {

  const_pool_in.clear();
  const_pool_in.push_back(NULL); // dummy first item

  u2 cp_count = get_u2be(p);
  for (int ii = 1; ii < cp_count; ++ii) {
    u1 tag = get_u1(p);

    if (devtools_ijar::verbose) {
      fprintf(stderr, "cp[%d/%d] = tag %d\n", ii, cp_count, tag);
    }

    switch(tag) {
      case CONSTANT_Class: {
        u2 name_index = get_u2be(p);
        const_pool_in.push_back(new Constant_Class(name_index));
        break;
      }
      case CONSTANT_FieldRef:
      case CONSTANT_Methodref:
      case CONSTANT_Interfacemethodref: {
        u2 class_index = get_u2be(p);
        u2 nti = get_u2be(p);
        const_pool_in.push_back(new Constant_FMIref(tag, class_index, nti));
        break;
      }
      case CONSTANT_String: {
        u2 string_index = get_u2be(p);
        const_pool_in.push_back(new Constant_String(string_index));
        break;
      }
      case CONSTANT_NameAndType: {
        u2 name_index = get_u2be(p);
        u2 descriptor_index = get_u2be(p);
        const_pool_in.push_back(
            new Constant_NameAndType(name_index, descriptor_index));
        break;
      }
      case CONSTANT_Utf8: {
        u2 length = get_u2be(p);
        if (devtools_ijar::verbose) {
          fprintf(stderr, "Utf8: \"%s\" (%d)\n",
                  std::string((const char*) p, length).c_str(), length);
        }

        const_pool_in.push_back(new Constant_Utf8(length, p));
        p += length;
        break;
      }
      case CONSTANT_Integer:
      case CONSTANT_Float: {
        u4 bytes = get_u4be(p);
        const_pool_in.push_back(new Constant_IntegerOrFloat(tag, bytes));
        break;
      }
      case CONSTANT_Long:
      case CONSTANT_Double: {
        u4 high_bytes = get_u4be(p);
        u4 low_bytes = get_u4be(p);
        const_pool_in.push_back(
            new Constant_LongOrDouble(tag, high_bytes, low_bytes));
        // Longs and doubles occupy two constant pool slots.
        // ("In retrospect, making 8-byte constants take two "constant
        // pool entries was a poor choice." --JVM Spec.)
        const_pool_in.push_back(NULL);
        ii++;
        break;
      }
      case CONSTANT_MethodHandle: {
        u1 reference_kind = get_u1(p);
        u2 reference_index = get_u2be(p);
        const_pool_in.push_back(
            new Constant_MethodHandle(reference_kind, reference_index));
        break;
      }
      case CONSTANT_MethodType: {
        u2 descriptor_index = get_u2be(p);
        const_pool_in.push_back(new Constant_MethodType(descriptor_index));
        break;
      }
      case CONSTANT_Dynamic: {
        u2 bootstrap_method_attr = get_u2be(p);
        u2 name_name_type_index = get_u2be(p);
        const_pool_in.push_back(
            new Constant_Dynamic(bootstrap_method_attr, name_name_type_index));
        break;
      }
      case CONSTANT_InvokeDynamic: {
        u2 bootstrap_method_attr = get_u2be(p);
        u2 name_name_type_index = get_u2be(p);
        const_pool_in.push_back(new Constant_InvokeDynamic(
            bootstrap_method_attr, name_name_type_index));
        break;
      }
      default: {
        fprintf(stderr, "Unknown constant: %hhu. Passing class through.\n",
                tag);
        return false;
      }
    }
  }

  return true;
}

bool ClassFile::IsLocalOrAnonymous() {
  for (const Attribute *attribute : attributes) {
    if (attribute->attribute_name_->Display() == "EnclosingMethod") {
      // JVMS 4.7.6: a class must has EnclosingMethod attribute iff it
      // represents a local class or an anonymous class
      return true;
    }
  }
  return false;
}

static bool HasKeepForCompile(const std::vector<Attribute *> attributes) {
  for (const Attribute *attribute : attributes) {
    if (attribute->KeepForCompile()) {
      return true;
    }
  }
  return false;
}

bool ClassFile::KeepForCompile() {
  if (HasKeepForCompile(attributes)) {
    return true;
  }
  return false;
}

static ClassFile *ReadClass(const void *classdata, size_t length) {
  const u1 *p = (u1*) classdata;

  ClassFile *clazz = new ClassFile;

  clazz->length = length;

  clazz->magic = get_u4be(p);
  if (clazz->magic != 0xCAFEBABE) {
    fprintf(stderr, "Bad magic %" PRIx32 "\n", clazz->magic);
    abort();
  }
  clazz->major = get_u2be(p);
  clazz->minor = get_u2be(p);

  if (!clazz->ReadConstantPool(p)) {
    delete clazz;
    return NULL;
  }

  clazz->access_flags = get_u2be(p);
  clazz->this_class = constant(get_u2be(p));
  class_name = clazz->this_class;

  u2 super_class_id = get_u2be(p);
  clazz->super_class = super_class_id == 0 ? NULL : constant(super_class_id);

  u2 interfaces_count = get_u2be(p);
  for (int ii = 0; ii < interfaces_count; ++ii) {
    clazz->interfaces.push_back(constant(get_u2be(p)));
  }

  u2 fields_count = get_u2be(p);
  for (int ii = 0; ii < fields_count; ++ii) {
    Member *field = Member::Read(p);

    if ((field->access_flags & ACC_PRIVATE) == ACC_PRIVATE) {
      // drop private fields
      continue;
    }
    clazz->fields.push_back(field);
  }

  u2 methods_count = get_u2be(p);
  for (int ii = 0; ii < methods_count; ++ii) {
    Member *method = Member::Read(p);

    // drop class initializers
    if (method->name->Display() == "<clinit>") continue;

    if ((method->access_flags & ACC_PRIVATE) == ACC_PRIVATE) {
      // drop private methods
      continue;
    }
    if ((method->access_flags & (ACC_SYNTHETIC | ACC_BRIDGE | ACC_PUBLIC |
                                 ACC_PROTECTED)) == ACC_SYNTHETIC) {
      // drop package-private non-bridge synthetic methods, e.g. synthetic
      // constructors used to instantiate private nested classes within their
      // declaring compilation unit
      continue;
    }
    clazz->methods.push_back(method);
  }

  clazz->ReadAttrs(p);

  return clazz;
}

// In theory, '/' is also reserved, but it's okay if we just parse package
// identifiers as part of the class name. Note that signatures are UTF-8, but
// this works just as well as in plain ASCII.
static const char *SIGNATURE_NON_IDENTIFIER_CHARS = ".;[<>:";

void Expect(const std::string& desc, size_t* p, char expected) {
  if (desc[*p] != expected) {
    fprintf(stderr, "Expected '%c' in '%s' at %zd in signature\n",
            expected, desc.substr(*p).c_str(), *p);
    exit(1);
  }

  *p += 1;
}

// These functions form a crude recursive descent parser for descriptors and
// signatures in class files (see JVM spec 4.3).
//
// This parser is a bit more liberal than the spec, but this should be fine,
// because it accepts all valid class files and croaks only on invalid ones.
void ParseFromClassTypeSignature(const std::string& desc, size_t* p);
void ParseSimpleClassTypeSignature(const std::string& desc, size_t* p);
void ParseClassTypeSignatureSuffix(const std::string& desc, size_t* p);
void ParseIdentifier(const std::string& desc, size_t* p);
void ParseTypeArgumentsOpt(const std::string& desc, size_t* p);
void ParseMethodDescriptor(const std::string& desc, size_t* p);

void ParseClassTypeSignature(const std::string& desc, size_t* p) {
  Expect(desc, p, 'L');
  ParseSimpleClassTypeSignature(desc, p);
  ParseClassTypeSignatureSuffix(desc, p);
  Expect(desc, p, ';');
}

void ParseSimpleClassTypeSignature(const std::string& desc, size_t* p) {
  ParseIdentifier(desc, p);
  ParseTypeArgumentsOpt(desc, p);
}

void ParseClassTypeSignatureSuffix(const std::string& desc, size_t* p) {
  while (desc[*p] == '.') {
    *p += 1;
    ParseSimpleClassTypeSignature(desc, p);
  }
}

void ParseIdentifier(const std::string& desc, size_t* p) {
  size_t next = desc.find_first_of(SIGNATURE_NON_IDENTIFIER_CHARS, *p);
  std::string id = desc.substr(*p, next - *p);
  used_class_names.insert(id);
  *p = next;
}

void ParseTypeArgumentsOpt(const std::string& desc, size_t* p) {
  if (desc[*p] != '<') {
    return;
  }

  *p += 1;
  while (desc[*p] != '>') {
    switch (desc[*p]) {
      case '*':
        *p += 1;
        break;

      case '+':
      case '-':
        *p += 1;
        ExtractClassNames(desc, p);
        break;

      default:
        ExtractClassNames(desc, p);
        break;
    }
  }

  *p += 1;
}

void ParseMethodDescriptor(const std::string& desc, size_t* p) {
  Expect(desc, p, '(');
  while (desc[*p] != ')') {
    ExtractClassNames(desc, p);
  }

  Expect(desc, p, ')');
  ExtractClassNames(desc, p);
}

void ParseFormalTypeParameters(const std::string& desc, size_t* p) {
  Expect(desc, p, '<');
  while (desc[*p] != '>') {
    ParseIdentifier(desc, p);
    Expect(desc, p, ':');
    if (desc[*p] != ':' && desc[*p] != '>') {
      ExtractClassNames(desc, p);
    }

    while (desc[*p] == ':') {
      Expect(desc, p, ':');
      ExtractClassNames(desc, p);
    }
  }

  Expect(desc, p, '>');
}

void ExtractClassNames(const std::string& desc, size_t* p) {
  switch (desc[*p]) {
    case '<':
      ParseFormalTypeParameters(desc, p);
      ExtractClassNames(desc, p);
      break;

    case 'L':
      ParseClassTypeSignature(desc, p);
      break;

    case '[':
      *p += 1;
      ExtractClassNames(desc, p);
      break;

    case 'T':
      *p += 1;
      ParseIdentifier(desc, p);
      Expect(desc, p, ';');
      break;

    case '(':
      ParseMethodDescriptor(desc, p);
      break;

    case 'B':
    case 'C':
    case 'D':
    case 'F':
    case 'I':
    case 'J':
    case 'S':
    case 'Z':
    case 'V':
      *p += 1;
      break;

    default:
      fprintf(stderr, "Invalid signature %s\n", desc.substr(*p).c_str());
  }
}

void ClassFile::WriteClass(u1 *&p) {
  used_class_names.clear();
  std::vector<Member *> members;
  members.insert(members.end(), fields.begin(), fields.end());
  members.insert(members.end(), methods.begin(), methods.end());
  ExtractClassNames();
  for (auto *member : members) {
    size_t idx = 0;
    devtools_ijar::ExtractClassNames(member->descriptor->Display(), &idx);
    member->ExtractClassNames();
  }

  // We have to write the body out before the header in order to reference
  // the essential constants and populate the output constant pool:
  u1 *body = new u1[length];
  u1 *q = body;
  WriteBody(q); // advances q
  u4 body_length = q - body;

  WriteHeader(p); // advances p
  put_n(p, body, body_length);
  delete[] body;
}

bool StripClass(u1 *&classdata_out, const u1 *classdata_in, size_t in_length) {
  ClassFile *clazz = ReadClass(classdata_in, in_length);
  bool keep = true;
  if (clazz == NULL || clazz->KeepForCompile()) {
    // Class is invalid or kept. Simply copy it to the output and call it a day.
    put_n(classdata_out, classdata_in, in_length);
  } else if (clazz->IsLocalOrAnonymous()) {
    keep = false;
  } else {
    // Constant pool item zero is a dummy entry.  Setting it marks the
    // beginning of the output phase; calls to Constant::slot() will
    // fail if called prior to this.
    const_pool_out.push_back(NULL);
    clazz->WriteClass(classdata_out);

    delete clazz;
  }

  // Now clean up all the mess we left behind.

  for (size_t i = 0; i < const_pool_in.size(); i++) {
    delete const_pool_in[i];
  }

  const_pool_in.clear();
  const_pool_out.clear();
  return keep;
}

}  // namespace devtools_ijar
